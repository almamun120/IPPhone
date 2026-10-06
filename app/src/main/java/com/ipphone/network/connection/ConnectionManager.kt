package com.ipphone.network.connection

import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.network.protocol.*
import com.ipphone.utils.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val userDao: UserDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, ProtocolMessage>>(
        extraBufferCapacity = 100
    )
    val incomingMessages: SharedFlow<Pair<String, ProtocolMessage>> =
        _incomingMessages.asSharedFlow()

    private val _connectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val connectedDevices: StateFlow<Set<String>> = _connectedDevices.asStateFlow()

    private val activeConnections = ConcurrentHashMap<String, ConnectionWrapper>()
    private var serverSocket: ServerSocket? = null

    @Volatile private var boundPort: Int = 0

    private val connectionLocks = ConcurrentHashMap<String, Mutex>()

    data class ConnectionWrapper(
        val deviceId: String,
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        var lastActivity: Long = System.currentTimeMillis()
    )

    // ─────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────

    fun start(port: Int): Int {
        if (_isRunning.value) {
            return boundPort.takeIf { it > 0 } ?: port
        }

        val deferred = CompletableDeferred<Int>()

        scope.launch {
            startServer(port, deferred)
        }

        return runBlocking {
            try {
                withTimeout(3_000) { deferred.await() }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start TCP server; returning 0")
                0
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.d("Server socket already closed: ${e.message}")
        }
        serverSocket = null

        activeConnections.values.forEach { wrapper ->
            try {
                if (!wrapper.socket.isClosed) {
                    wrapper.socket.close()
                }
            } catch (e: Exception) {
                Timber.d("Error closing connection to ${wrapper.deviceId}: ${e.message}")
            }
        }
        activeConnections.clear()
        connectionLocks.clear()
        _connectedDevices.value = emptySet()
        boundPort = 0
    }

    suspend fun sendMessage(deviceId: String, message: ProtocolMessage): Boolean {
        return withContext(Dispatchers.IO) {
            val resolvedId = resolveDeviceId(deviceId)

            repeat(2) { attempt ->
                try {
                    val wrapper = getOrCreateConnection(resolvedId)
                        ?: return@withContext false

                    val data = PacketSerializer.serialize(ensureSenderId(message))
                    val sent = try {
                        synchronized(wrapper.output) {
                            wrapper.output.write(data)
                            wrapper.output.flush()
                        }
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Internal write failure to $resolvedId")
                        false
                    }

                    if (sent) {
                        wrapper.lastActivity = System.currentTimeMillis()
                        return@withContext true
                    } else {
                        throw SocketException("Write failed")
                    }
                } catch (e: Exception) {
                    if (attempt == 0) {
                        Timber.w("First attempt to send message to $resolvedId failed (${e.message}), retrying...")
                    } else {
                        Timber.e(e, "Failed to send message to $resolvedId after retry")
                    }

                    activeConnections.remove(resolvedId)?.let { wrapper ->
                        try { wrapper.socket.close() } catch (_: Exception) {}
                        _connectedDevices.update { it - resolvedId }
                    }
                }
            }
            false
        }
    }

    private suspend fun resolveDeviceId(deviceId: String): String {
        if (!deviceId.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$"""))) {
            return deviceId
        }
        return try {
            val existing = userDao.getUserByIp(deviceId)
            if (existing != null && existing.uuid != deviceId) {
                Timber.d("resolveDeviceId: resolved IP $deviceId → UUID ${existing.uuid}")
                existing.uuid
            } else {
                deviceId
            }
        } catch (e: Exception) {
            Timber.e(e, "resolveDeviceId failed for $deviceId")
            deviceId
        }
    }

    /**
     * Best-known IPv4 address of a peer: the live control connection first,
     * then the last address learnt from heartbeats/discovery.
     */
    suspend fun resolvePeerAddress(deviceId: String): String? {
        val resolved = resolveDeviceId(deviceId)
        activeConnections[resolved]?.socket?.inetAddress?.hostAddress?.let { return it }
        return try {
            userDao.getUserById(resolved)?.ipAddress?.takeIf { it.isNotBlank() }
                ?: resolved.takeIf { it.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) }
        } catch (e: Exception) {
            Timber.w(e, "resolvePeerAddress failed for $deviceId")
            null
        }
    }

    fun closeConnection(deviceId: String) {
        activeConnections.remove(deviceId)?.let { wrapper ->
            _connectedDevices.update { it - deviceId }
            try {
                if (!wrapper.socket.isClosed) {
                    wrapper.socket.close()
                }
            } catch (e: Exception) {
                Timber.d("Socket already closed for $deviceId: ${e.message}")
            }
        }
        connectionLocks.remove(deviceId)
    }

    fun isConnected(deviceId: String): Boolean =
        activeConnections[deviceId]?.socket?.isConnected == true

    fun getActiveConnectionCount(): Int = activeConnections.size

    fun getBoundPort(): Int = boundPort

    private suspend fun ensureSenderId(message: ProtocolMessage): ProtocolMessage {
        if (message.senderId.isNotEmpty()) return message

        val myId = preferencesManager.getDeviceUuid()

        return when (message) {
            is PairRequestMessage        -> message.copy(senderId = myId)
            is PairResponseMessage       -> message.copy(senderId = myId)
            is UnpairMessage             -> message.copy(senderId = myId)
            is HeartbeatMessage          -> message.copy(senderId = myId)
            is ChatMessage               -> message.copy(senderId = myId)
            is MessageReceiptMessage     -> message.copy(senderId = myId)
            is MessageEditMessage        -> message.copy(senderId = myId)
            is MessageDeleteMessage      -> message.copy(senderId = myId)
            is TypingIndicatorMessage    -> message.copy(senderId = myId)
            is FileOfferMessage          -> message.copy(senderId = myId)
            is FileResponseMessage       -> message.copy(senderId = myId)
            is FileChunkMessage          -> message.copy(senderId = myId)
            is ChunkAckMessage           -> message.copy(senderId = myId)
            is TransferControlMessage    -> message.copy(senderId = myId)
            is ResumeRequestMessage      -> message.copy(senderId = myId)
            is ResumeResponseMessage     -> message.copy(senderId = myId)
            is CallOfferMessage          -> message.copy(senderId = myId)
            is CallAnswerMessage         -> message.copy(senderId = myId)
            is IceCandidateMessage       -> message.copy(senderId = myId)
            is CallEndMessage            -> message.copy(senderId = myId)
            is BlockedNotificationMessage -> message.copy(senderId = myId)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Internal — server
    // ─────────────────────────────────────────────────────────

    private suspend fun startServer(port: Int, readySignal: CompletableDeferred<Int>) {
        withContext(Dispatchers.IO) {
            try {
                try { serverSocket?.close() } catch (_: Exception) {}

                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    try {
                        bind(InetSocketAddress(port))
                    } catch (e: BindException) {
                        Timber.w("Port $port already in use, falling back to an ephemeral port")
                        bind(InetSocketAddress(0))
                    }
                }

                boundPort = serverSocket!!.localPort
                _isRunning.value = true

                Timber.d("TCP Server started on port $boundPort")
                readySignal.complete(boundPort)

                while (_isRunning.value) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        scope.launch { handleIncomingConnection(clientSocket) }
                    } catch (e: Exception) {
                        if (_isRunning.value) Timber.e(e, "Error accepting connection")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start TCP server on port $port")
                _isRunning.value = false
                if (!readySignal.isCompleted) readySignal.completeExceptionally(e)
            }
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            var input: DataInputStream? = null
            var output: DataOutputStream? = null
            var senderId: String? = null

            try {
                input  = DataInputStream(BufferedInputStream(socket.getInputStream(), 512 * 1024))
                output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 512 * 1024))
                socket.soTimeout = 10_000
                socket.sendBufferSize    = 2 * 1024 * 1024
                socket.receiveBufferSize = 2 * 1024 * 1024
                socket.tcpNoDelay        = true

                val lengthBytes = ByteArray(4)

                try {
                    input.readFully(lengthBytes)
                } catch (e: EOFException) {
                    Timber.w("Connection from ${socket.inetAddress} closed before handshake completed")
                    socket.close()
                    return@withContext
                }

                val messageLength = PacketSerializer.readMessageLength(lengthBytes)

                if (!PacketSerializer.isValidMessageSize(messageLength)) {
                    Timber.w("Invalid handshake size $messageLength from ${socket.inetAddress}")
                    socket.close()
                    return@withContext
                }

                val messageBytes = ByteArray(messageLength)

                try {
                    input.readFully(messageBytes)
                } catch (e: EOFException) {
                    Timber.w("Connection from ${socket.inetAddress} closed during handshake read")
                    socket.close()
                    return@withContext
                }

                val message = PacketSerializer.deserialize(messageBytes)
                if (message == null) {
                    Timber.w("Could not deserialize handshake from ${socket.inetAddress}")
                    socket.close()
                    return@withContext
                }

                senderId = message.senderId

                // Send our Heartbeat as response to complete the handshake
                val myId = preferencesManager.getDeviceUuid()
                val myName = preferencesManager.getDeviceName()
                val handshakeResponse = HeartbeatMessage(
                    senderId = myId,
                    deviceName = myName,
                    tcpPort = boundPort
                )
                val responseData = PacketSerializer.serialize(handshakeResponse)
                synchronized(output) {
                    output.write(responseData)
                    output.flush()
                }

                // Close any existing stale connection to this device
                activeConnections[senderId]?.let { oldWrapper ->
                    Timber.d("Closing existing connection to $senderId to favour new incoming one")
                    try { oldWrapper.socket.close() } catch (_: Exception) {}
                    activeConnections.remove(senderId)
                }

                if (userDao.isBlocked(senderId)) {
                    Timber.w("Rejected connection from blocked device: $senderId")
                    socket.close()
                    return@withContext
                }

                socket.soTimeout = 0

                val wrapper = ConnectionWrapper(
                    deviceId = senderId,
                    socket   = socket,
                    input    = input,
                    output   = output
                )

                activeConnections[senderId] = wrapper
                _connectedDevices.update { it + senderId }

                Timber.d("Accepted connection from $senderId @ ${socket.inetAddress}")

                _incomingMessages.emit(senderId to message)

                readFromConnection(wrapper)

            } catch (e: SocketTimeoutException) {
                Timber.w("Handshake timeout from ${socket.inetAddress}")
                socket.close()
            } catch (e: Exception) {
                Timber.e(e, "Error handling inbound connection from ${socket.inetAddress}")

                senderId?.let { id ->
                    activeConnections.remove(id)
                    _connectedDevices.update { it - id }
                }

                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun readFromConnection(wrapper: ConnectionWrapper) {
        withContext(Dispatchers.IO) {
            try {
                while (_isRunning.value && !wrapper.socket.isClosed) {
                    val lengthBytes = ByteArray(4)

                    try {
                        wrapper.input.readFully(lengthBytes)
                    } catch (e: EOFException) {
                        Timber.d("${wrapper.deviceId} closed connection")
                        break
                    }

                    val messageLength = PacketSerializer.readMessageLength(lengthBytes)

                    if (!PacketSerializer.isValidMessageSize(messageLength)) {
                        Timber.w("Invalid message size $messageLength from ${wrapper.deviceId}")
                        break
                    }

                    val messageBytes = ByteArray(messageLength)

                    try {
                        wrapper.input.readFully(messageBytes)
                    } catch (e: EOFException) {
                        Timber.w("${wrapper.deviceId} disconnected mid-message")
                        break
                    }

                    val message = PacketSerializer.deserialize(messageBytes)
                    if (message != null) {
                        wrapper.lastActivity = System.currentTimeMillis()
                        _incomingMessages.emit(wrapper.deviceId to message)
                    } else {
                        Timber.w("Failed to deserialize message from ${wrapper.deviceId}")
                    }
                }
            } catch (e: SocketTimeoutException) {
                Timber.d("Socket timeout for ${wrapper.deviceId}")
            } catch (e: SocketException) {
                Timber.d("Socket error for ${wrapper.deviceId}: ${e.message}")
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error reading from ${wrapper.deviceId}")
            } finally {
                activeConnections.remove(wrapper.deviceId)
                _connectedDevices.update { it - wrapper.deviceId }
                try {
                    wrapper.socket.close()
                    Timber.d("Closed connection to ${wrapper.deviceId}")
                } catch (_: Exception) {}
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Internal — outgoing connections
    // ─────────────────────────────────────────────────────────

    private suspend fun getOrCreateConnection(deviceId: String): ConnectionWrapper? {
        // Fast path: connection already exists and is valid
        activeConnections[deviceId]?.let { wrapper ->
            if (!wrapper.socket.isClosed) return wrapper
            activeConnections.remove(deviceId)
        }

        // Slow path: acquire per-device lock to prevent duplicate connections
        val mutex = connectionLocks.getOrPut(deviceId) { Mutex() }

        return mutex.withLock {
            // Double-check after acquiring lock
            activeConnections[deviceId]?.let { wrapper ->
                if (!wrapper.socket.isClosed) return@withLock wrapper
                activeConnections.remove(deviceId)
            }

            createConnection(deviceId)
        }
    }

    private suspend fun createConnection(deviceId: String): ConnectionWrapper? = withContext(Dispatchers.IO) {
        // ── FIX: Always re-read the DB row immediately before connecting.
        // PresenceManager.handleHeartbeat() updates ip_address and tcp_port on
        // every UDP heartbeat. If SyncManager cached the user object earlier
        // it may have a stale port. Reading fresh here ensures we use the port
        // that was updated by the most recent heartbeat.
        val user = userDao.getUserById(deviceId)

        var ipAddress: String
        var port: Int

        if (user == null || user.ipAddress.isNullOrEmpty()) {
            if (deviceId.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$"""))) {
                ipAddress = deviceId
                port = Constants.TCP_PORT

                val existingUser = userDao.getUserByIp(ipAddress)
                if (existingUser != null && existingUser.uuid != deviceId) {
                    Timber.d("createConnection: IP $ipAddress already known as UUID ${existingUser.uuid}, redirecting")
                    return@withContext getOrCreateConnection(existingUser.uuid)
                }
            } else {
                Timber.w("No known address for device $deviceId")
                return@withContext null
            }
        } else {
            ipAddress = user.ipAddress!!
            port = user.tcpPort

            // ── FIX: Validate port from DB.
            // If the stored port is 0 or negative it means the DB was written
            // before the peer's TCP server started (race condition on first
            // heartbeat). Fall back to the well-known port so we at least try.
            if (port <= 0) {
                Timber.w("Invalid port $port for $deviceId in DB, falling back to ${Constants.TCP_PORT}")
                port = Constants.TCP_PORT
            }
        }

        Timber.d("createConnection: $deviceId → $ipAddress:$port (from DB)")

        // ── FIX: Try up to 2 ports: the DB port and then Constants.TCP_PORT.
        // This handles the case where the peer's NetworkService restarted and
        // grabbed an ephemeral port that hasn't propagated to our DB yet (the
        // heartbeat hasn't been processed yet, or was processed with a delay).
        // We try the DB port first. If ECONNREFUSED, we try the well-known port.
        // On success we update the DB so subsequent attempts use the right port.
        val portsToTry = linkedSetOf(port, Constants.TCP_PORT).toList()

        for (tryPort in portsToTry) {
            val result = tryConnect(deviceId, ipAddress, tryPort)
            if (result != null) {
                // If we succeeded on a different port than what's in the DB, update it.
                if (tryPort != port) {
                    Timber.d("createConnection: connected on fallback port $tryPort (DB had $port), updating DB")
                    try {
                        user?.let { u ->
                            userDao.update(u.copy(tcpPort = tryPort, updatedAt = System.currentTimeMillis()))
                        }
                    } catch (e: Exception) {
                        Timber.w("Failed to update port in DB: ${e.message}")
                    }
                }
                return@withContext result
            }
            if (tryPort != portsToTry.last()) {
                Timber.d("createConnection: port $tryPort refused for $deviceId, trying ${portsToTry.last()}")
            }
        }

        Timber.e("createConnection: all ports exhausted for $deviceId @ $ipAddress")
        null
    }

    /**
     * Attempt a single TCP connection to [ipAddress]:[port].
     * Returns a ready [ConnectionWrapper] on success, null on any failure.
     */
    private suspend fun tryConnect(
        deviceId: String,
        ipAddress: String,
        port: Int
    ): ConnectionWrapper? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.sendBufferSize = 2 * 1024 * 1024
            socket.receiveBufferSize = 2 * 1024 * 1024
            socket.tcpNoDelay = true

            Timber.d("tryConnect: $deviceId @ $ipAddress:$port")
            socket.connect(InetSocketAddress(ipAddress, port), 5_000)
            socket.soTimeout = 10_000

            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 512 * 1024))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 512 * 1024))

            // Mandatory handshake: send our Heartbeat
            val myId = preferencesManager.getDeviceUuid()
            val myName = preferencesManager.getDeviceName()
            val handshake = HeartbeatMessage(
                senderId = myId,
                deviceName = myName,
                tcpPort = boundPort
            )

            val handshakeData = PacketSerializer.serialize(handshake)
            synchronized(output) {
                output.write(handshakeData)
                output.flush()
            }

            // Mandatory handshake: wait for peer's Heartbeat
            val lengthBytes = ByteArray(4)
            input.readFully(lengthBytes)
            val msgLen = PacketSerializer.readMessageLength(lengthBytes)
            if (!PacketSerializer.isValidMessageSize(msgLen)) throw SocketException("Invalid handshake size")

            val msgBytes = ByteArray(msgLen)
            input.readFully(msgBytes)
            val response = PacketSerializer.deserialize(msgBytes) as? HeartbeatMessage
                ?: throw SocketException("Handshake response was not a HeartbeatMessage")

            val actualUuid = response.senderId
            Timber.d("tryConnect: handshake OK → actualUuid=$actualUuid (requested=$deviceId) port=$port")

            socket.soTimeout = 0
            val wrapper = ConnectionWrapper(
                deviceId = actualUuid,
                socket = socket,
                input = input,
                output = output
            )

            // Update DB with resolved UUID, confirmed IP and port
            scope.launch {
                val existing = userDao.getUserById(actualUuid)
                val now = System.currentTimeMillis()
                if (existing == null) {
                    userDao.insert(
                        com.ipphone.data.local.database.entity.UserEntity(
                            uuid = actualUuid,
                            deviceName = response.deviceName,
                            profileImage = null,
                            ipAddress = ipAddress,
                            tcpPort = port,
                            pairingState = "NONE",
                            isBlocked = false,
                            blockedAt = null,
                            pairedAt = null,
                            lastSeen = now,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                } else {
                    userDao.update(
                        existing.copy(
                            deviceName = response.deviceName,
                            ipAddress = ipAddress,
                            tcpPort = port,
                            lastSeen = now,
                            updatedAt = now
                        )
                    )
                }

                // Clean up IP-based placeholder entry if it differs from real UUID
                if (deviceId != actualUuid && deviceId.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$"""))) {
                    try {
                        userDao.deleteById(deviceId)
                        Timber.d("Deleted IP placeholder entry $deviceId (resolved to $actualUuid)")
                    } catch (e: Exception) {
                        Timber.w("Could not delete IP placeholder $deviceId: ${e.message}")
                    }
                }
            }

            // Store connection keyed by the TRUE UUID
            activeConnections[actualUuid]?.let { old ->
                try { old.socket.close() } catch (_: Exception) {}
            }

            activeConnections[actualUuid] = wrapper
            _connectedDevices.update { it + actualUuid }

            scope.launch { readFromConnection(wrapper) }

            wrapper
        } catch (e: Exception) {
            Timber.d("tryConnect failed for $deviceId @ $ipAddress:$port — ${e.message}")
            try { socket?.close() } catch (_: Exception) {}
            null
        }
    }
}