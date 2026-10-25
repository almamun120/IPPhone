package com.ipphone.network.filetransfer

import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.domain.repository.FileTransferRepository
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.filetransfer.engine.FileTransferEngine
import com.ipphone.network.protocol.FileResponseMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logic shared by [LiveTransferManager] (ShareIt-style sending) and [ChatTransferManager]
 * (attachments inside a conversation): answering an offer, wiring the receiver's data port
 * into the engine and cleaning up after a failure. The two managers only differ in
 * *where files are saved* and *what happens when they finish*.
 */
@Singleton
class TransferCore @Inject constructor(
    private val engine: FileTransferEngine,
    private val connectionManager: ConnectionManager,
    private val fileTransferRepository: FileTransferRepository,
    private val preferencesManager: PreferencesManager,
    private val statsTracker: TransferStatsTracker
) {

    /**
     * Receiver side, step 1: register the incoming transfer with the engine and make sure the
     * bulk data server is listening. Returns the port, or 0 when it could not be opened.
     */
    fun prepareIncoming(transferId: String, resolver: suspend (fileId: String) -> String?): Int {
        engine.registerIncoming(transferId, resolver)
        return engine.ensureDataServer()
    }

    /** Receiver side, step 2: tell the sender "go ahead, stream to this port". */
    suspend fun sendAccept(peerId: String, transferId: String, dataPort: Int): Boolean =
        connectionManager.sendMessage(
            peerId,
            FileResponseMessage(
                senderId   = preferencesManager.getDeviceUuid(),
                transferId = transferId,
                accepted   = true,
                dataPort   = dataPort
            )
        )

    /** Receiver side: refuse an offer (also used when the data port cannot be opened). */
    suspend fun sendReject(peerId: String, transferId: String, reason: String?): Boolean =
        connectionManager.sendMessage(
            peerId,
            FileResponseMessage(
                senderId   = preferencesManager.getDeviceUuid(),
                transferId = transferId,
                accepted   = false,
                reason     = reason
            )
        )

    /** Sender side: the receiver accepted and told us where to stream. */
    fun onAccepted(transferId: String, dataPort: Int) = engine.setPeerDataPort(transferId, dataPort)

    /** Drop all in-memory state the engine and stats tracker keep for a transfer. */
    fun cleanup(transferId: String) {
        statsTracker.remove(transferId)
        engine.releaseTransfer(transferId)
    }

    /** Mark a transfer as failed and release everything it held. */
    suspend fun fail(transferId: String, reason: String) {
        cleanup(transferId)
        fileTransferRepository.markAsFailed(transferId, reason)
    }
}
