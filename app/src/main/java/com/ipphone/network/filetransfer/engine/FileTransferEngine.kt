package com.ipphone.network.filetransfer.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File transfer engine — "SHAREit style" bulk streaming.
 *
 * File bytes no longer travel as Base64-in-JSON over the shared control connection.
 * Instead every file gets its own TCP socket and is streamed as a raw binary stream:
 *
 *   sender   -> receiver : MAGIC | transferId | fileId | requestedOffset | fileSize
 *   receiver -> sender   : acceptedOffset (-1 = refused)      (resume point)
 *   sender   -> receiver : raw bytes from acceptedOffset to end, then CRC32 of those bytes
 *   receiver -> sender   : 1 = OK, 0 = checksum mismatch
 *
 * The receiver's data port is announced in [com.ipphone.network.protocol.FileResponseMessage.dataPort]
 * and handed to the engine through [setPeerDataPort].
 */
@Singleton
class FileTransferEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Sending state (key = "${transferId}_$fileId") ---
    private val activeSendJobs    = ConcurrentHashMap<String, Job>()
    private val activeSendSockets = ConcurrentHashMap<String, Socket>()
    private val peerDataPorts     = ConcurrentHashMap<String, Int>()      // transferId -> receiver data port

    // --- Receiving state ---
    private val incomingResolvers    = ConcurrentHashMap<String, suspend (String) -> String?>()
    private val activeReceiveSockets = ConcurrentHashMap<String, Socket>()
    private val receiveLocks         = ConcurrentHashMap<String, Mutex>()

    // Transfers that were paused/cancelled on purpose: socket errors for them are not failures.
    private val stoppedTransfers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val serverLock = Any()
    @Volatile private var dataServer: ServerSocket? = null
    @Volatile private var dataPort: Int = 0

    private val _engineEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 128)
    val engineEvents = _engineEvents.asSharedFlow()

    sealed class EngineEvent {
        data class Progress(
            val transferId: String,
            val fileId: String,
            val bytesSent: Long,
            val totalBytes: Long
        ) : EngineEvent()
        data class FileCompleted(
            val transferId: String,
            val fileId: String,
            val path: String
        ) : EngineEvent()
        data class TransferCompleted(val transferId: String) : EngineEvent()
        data class Error(
            val transferId: String,
            val fileId: String?,
            val message: String
        ) : EngineEvent()
    }

    // ─────────────────────────────────────────────────────────
    //  Receiver-side setup
    // ─────────────────────────────────────────────────────────

    /**
     * Starts the bulk data server if needed and returns the port the peer must connect to
     * (0 on failure). Call this before answering a file offer with "accepted".
     */
    fun ensureDataServer(): Int {
        val existing = dataServer
        if (existing != null && !existing.isClosed) return dataPort

        return synchronized(serverLock) {
            val again = dataServer
            if (again != null && !again.isClosed) {
                dataPort
            } else {
                try {
                    val server = ServerSocket()
                    server.reuseAddress = true
                    server.receiveBufferSize = Constants.SOCKET_BUFFER_SIZE
                    try {
                        server.bind(InetSocketAddress(Constants.DATA_PORT))
                    } catch (e: BindException) {
                        Timber.w("Data port ${Constants.DATA_PORT} busy, using an ephemeral port")
                        server.bind(InetSocketAddress(0))
                    }
                    dataServer = server
                    dataPort = server.localPort
                    Timber.d("Data server listening on port $dataPort")
                    scope.launch { acceptLoop(server) }
                    dataPort
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start data server")
                    0
                }
            }
        }
    }

    /**
     * Registers an accepted incoming transfer. [resolver] maps a fileId to the local path
     * where that file must be written (or null to refuse the stream).
     */
    fun registerIncoming(transferId: String, resolver: suspend (String) -> String?) {
        incomingResolvers[transferId] = resolver
        stoppedTransfers.remove(transferId)
    }

    /** Remembers the receiver's data port for an outgoing transfer. */
    fun setPeerDataPort(transferId: String, port: Int) {
        if (port > 0) peerDataPorts[transferId] = port
    }

    /** Forget everything the engine keeps for a finished/failed/cancelled transfer. */
    fun releaseTransfer(transferId: String) {
        incomingResolvers.remove(transferId)
        peerDataPorts.remove(transferId)
        receiveLocks.keys.filter { it.startsWith(transferId) }.forEach { receiveLocks.remove(it) }
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                scope.launch { handleIncomingData(socket) }
            } catch (e: Exception) {
                if (server.isClosed) break
                Timber.w(e, "Data server accept error")
                delay(200)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Receiving
    // ─────────────────────────────────────────────────────────

    private suspend fun handleIncomingData(socket: Socket) {
        var key: String? = null
        var transferId = ""
        var fileId = ""
        var raf: RandomAccessFile? = null
        var completed = false

        try {
            socket.soTimeout = Constants.TRANSFER_TIMEOUT_MS.toInt()
            socket.tcpNoDelay = true

            val input  = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1024))

            if (input.readInt() != STREAM_MAGIC) throw IOException("Unknown data stream")
            transferId = input.readUTF()
            fileId = input.readUTF()
            val requestedOffset = input.readLong()
            val fileSize = input.readLong()
            val k = "${transferId}_$fileId"
            key = k

            val path = incomingResolvers[transferId]?.invoke(fileId)
            if (path == null) {
                Timber.w("Refusing data stream for unknown transfer $transferId")
                output.writeLong(-1L)
                output.flush()
                return
            }

            stoppedTransfers.remove(transferId)

            // A newer connection for the same file (resume) supersedes the old one.
            activeReceiveSockets.put(k, socket)?.let { closeQuietly(it) }

            val lock = receiveLocks.getOrPut(k) { Mutex() }
            lock.withLock {
                val file = File(path)
                file.parentFile?.mkdirs()
                val r = RandomAccessFile(file, "rw")
                raf = r

                // Resume point: never trust more than what is really on disk.
                val accepted = requestedOffset.coerceIn(0L, r.length())
                r.setLength(accepted)
                r.seek(accepted)
                output.writeLong(accepted)
                output.flush()

                val crc = CRC32()
                val buf = ByteArray(Constants.STREAM_BUFFER_SIZE)
                var remaining = fileSize - accepted
                var received = accepted
                var lastEmit = 0L

                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw EOFException("Stream ended at $received of $fileSize bytes")
                    r.write(buf, 0, n)
                    crc.update(buf, 0, n)
                    remaining -= n
                    received += n

                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= Constants.PROGRESS_INTERVAL_MS) {
                        lastEmit = now
                        _engineEvents.tryEmit(EngineEvent.Progress(transferId, fileId, received, fileSize))
                    }
                }

                val senderCrc = input.readLong()
                val ok = senderCrc == crc.value
                output.writeByte(if (ok) 1 else 0)
                output.flush()

                try { r.close() } catch (_: Exception) {}
                raf = null

                if (ok) {
                    completed = true
                    _engineEvents.emit(EngineEvent.Progress(transferId, fileId, fileSize, fileSize))
                    _engineEvents.emit(EngineEvent.FileCompleted(transferId, fileId, path))
                } else {
                    _engineEvents.emit(
                        EngineEvent.Error(transferId, fileId, "Checksum mismatch — file corrupted in transit")
                    )
                }
            }
            if (completed) receiveLocks.remove(k)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val k = key
            val superseded = k != null && activeReceiveSockets[k] !== socket
            if (!completed && k != null && !superseded) {
                // Give a PAUSE/CANCEL control message time to arrive before calling it an error.
                delay(1500)
                if (!stoppedTransfers.contains(transferId) && activeReceiveSockets[k] === socket) {
                    Timber.e(e, "Receive failed for $k")
                    _engineEvents.emit(EngineEvent.Error(transferId, fileId, e.message ?: "Receive failed"))
                }
            }
        } finally {
            try { raf?.close() } catch (_: Exception) {}
            closeQuietly(socket)
            key?.let { activeReceiveSockets.remove(it, socket) }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Sending
    // ─────────────────────────────────────────────────────────

    /**
     * Streams one file to the peer. [startChunk] is kept for compatibility with the
     * resume logic: the stream restarts at startChunk * MAX_CHUNK_SIZE (the receiver may
     * move it back if it has less data on disk).
     */
    fun sendFile(
        peerId: String,
        transferId: String,
        fileId: String,
        filePath: String,
        fileSize: Long,
        startChunk: Int = 0
    ): Job {
        val jobKey = "${transferId}_$fileId"
        activeSendJobs.remove(jobKey)?.cancel()
        activeSendSockets.remove(jobKey)?.let { closeQuietly(it) }
        stoppedTransfers.remove(transferId)

        val requestedOffset = startChunk.toLong() * Constants.MAX_CHUNK_SIZE.toLong()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            var socket: Socket? = null
            try {
                val port = peerDataPorts[transferId]
                    ?: throw IOException("Receiver data port unknown — please start the transfer again")
                val host = connectionManager.resolvePeerAddress(peerId)
                    ?: throw IOException("Receiver address unknown")
                val size = resolveSize(filePath, fileSize)

                val s = Socket()
                socket = s
                s.sendBufferSize = Constants.SOCKET_BUFFER_SIZE
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), Constants.STREAM_CONNECT_TIMEOUT_MS)
                s.soTimeout = Constants.TRANSFER_TIMEOUT_MS.toInt()
                activeSendSockets[jobKey] = s

                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1024))

                out.writeInt(STREAM_MAGIC)
                out.writeUTF(transferId)
                out.writeUTF(fileId)
                out.writeLong(requestedOffset)
                out.writeLong(size)
                out.flush()

                val offset = inp.readLong()
                if (offset < 0) throw IOException("Receiver refused the data stream")
                if (offset > size) throw IOException("Invalid resume offset")

                openSource(filePath, offset).use { stream ->
                    val buf = ByteArray(Constants.STREAM_BUFFER_SIZE)
                    val crc = CRC32()
                    var remaining = size - offset
                    var sent = offset
                    var lastEmit = 0L

                    while (remaining > 0) {
                        ensureActive()
                        val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) throw EOFException("File ended early at $sent of $size bytes")
                        out.write(buf, 0, n)
                        crc.update(buf, 0, n)
                        remaining -= n
                        sent += n

                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= Constants.PROGRESS_INTERVAL_MS) {
                            lastEmit = now
                            _engineEvents.tryEmit(EngineEvent.Progress(transferId, fileId, sent, size))
                        }
                    }
                    out.writeLong(crc.value)
                    out.flush()
                }

                val status = inp.readByte().toInt()
                if (status != 1) throw IOException("Receiver reported a checksum mismatch")

                _engineEvents.emit(EngineEvent.Progress(transferId, fileId, size, size))
                _engineEvents.emit(EngineEvent.FileCompleted(transferId, fileId, filePath))
            } catch (e: CancellationException) {
                Timber.d("Send of $jobKey stopped")
            } catch (e: Exception) {
                if (stoppedTransfers.contains(transferId) || self?.isActive == false) {
                    Timber.d("Send of $jobKey interrupted (paused/cancelled/replaced)")
                } else {
                    Timber.e(e, "Error sending file $fileId")
                    _engineEvents.emit(EngineEvent.Error(transferId, fileId, e.message ?: "Unknown error"))
                }
            } finally {
                socket?.let {
                    closeQuietly(it)
                    activeSendSockets.remove(jobKey, it)
                }
                if (self != null) activeSendJobs.remove(jobKey, self)
            }
        }
        activeSendJobs[jobKey] = job
        job.start()
        return job
    }

    private fun resolveSize(filePath: String, declared: Long): Long {
        if (declared > 0) return declared
        return try {
            if (filePath.startsWith("/")) {
                File(filePath).length()
            } else {
                context.contentResolver.openFileDescriptor(Uri.parse(filePath), "r")
                    ?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /** Opens [filePath] (absolute path or content:// URI) positioned at [offset]. */
    private fun openSource(filePath: String, offset: Long): InputStream {
        if (filePath.startsWith("/")) {
            val fis = FileInputStream(File(filePath))
            try {
                if (offset > 0) fis.channel.position(offset)
            } catch (e: Exception) {
                fis.close()
                throw e
            }
            return fis
        }

        val uri = Uri.parse(filePath)

        // Preferred: a seekable file descriptor (fast, supports resume).
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                try {
                    if (offset > 0) fis.channel.position(offset)
                    return fis
                } catch (e: Exception) {
                    try { fis.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Timber.d("openFileDescriptor failed for $filePath, falling back to stream: ${e.message}")
        }

        // Fallback: plain stream + skip.
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open file: $filePath")
        var skipped = 0L
        while (skipped < offset) {
            val s = stream.skip(offset - skipped)
            if (s <= 0) break
            skipped += s
        }
        if (skipped < offset) {
            stream.close()
            throw IOException("Cannot seek to resume offset")
        }
        return stream
    }

    // ─────────────────────────────────────────────────────────
    //  Control
    // ─────────────────────────────────────────────────────────

    fun cancelTransfer(transferId: String) {
        stoppedTransfers.add(transferId)
        stopIo(transferId)
        releaseTransfer(transferId)
    }

    fun pauseTransfer(transferId: String) {
        stoppedTransfers.add(transferId)
        stopIo(transferId)
    }

    private fun stopIo(transferId: String) {
        activeSendJobs.keys.filter { it.startsWith(transferId) }
            .forEach { activeSendJobs.remove(it)?.cancel() }
        activeSendSockets.keys.filter { it.startsWith(transferId) }
            .forEach { k -> activeSendSockets.remove(k)?.let { closeQuietly(it) } }
        activeReceiveSockets.keys.filter { it.startsWith(transferId) }
            .forEach { k -> activeReceiveSockets.remove(k)?.let { closeQuietly(it) } }
    }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (_: Exception) {}
    }

    private companion object {
        const val STREAM_MAGIC = 0x49504458   // "IPDX"
    }
}
