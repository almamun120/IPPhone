package com.ipphone.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ipphone.MainActivity
import com.ipphone.R
import com.ipphone.IPPhoneApp
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.network.filetransfer.TransferStatsTracker
import com.ipphone.utils.Constants
import com.ipphone.utils.NotificationHelper
import com.ipphone.utils.toFormattedSize
import com.ipphone.utils.toTransferSpeed
import com.ipphone.utils.toFormattedDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class FileTransferService : Service() {

    @Inject lateinit var fileTransferManager: FileTransferManager
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var statsTracker: TransferStatsTracker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeTransferCount = 0

    companion object {
        const val ACTION_CANCEL_TRANSFER = "com.ipphone.CANCEL_TRANSFER"
        const val EXTRA_TRANSFER_ID      = "transfer_id"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("FileTransferService created")
        observeTransferEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
                transferId?.let { id ->
                    serviceScope.launch { fileTransferManager.cancelTransfer(id) }
                }
            }
        }
        startForeground(Constants.NOTIFICATION_ID_TRANSFER, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeTransferEvents() {
        serviceScope.launch {
            fileTransferManager.transferEvents.collect { event ->
                when (event) {
                    is FileTransferManager.TransferEvent.Progress -> {
                        updateProgressNotification(event.transferId, event.transferred, event.total)
                    }

                    is FileTransferManager.TransferEvent.Completed -> {
                        activeTransferCount--
                        showCompletedNotification(event.transferId)
                        checkAndStopSelf()
                    }

                    is FileTransferManager.TransferEvent.Failed,
                    is FileTransferManager.TransferEvent.Cancelled -> {
                        activeTransferCount--
                        checkAndStopSelf()
                    }

                    is FileTransferManager.TransferEvent.Offer -> {
                        activeTransferCount++
                        // FIX: Use NotificationHelper which now posts on CHANNEL_TRANSFER_INCOMING
                        // (IMPORTANCE_HIGH) with a fullScreenIntent so the notification wakes the
                        // screen even when the device is idle or the app is closed.
                        // The old code used CHANNEL_FILE_TRANSFER (IMPORTANCE_LOW) — that channel
                        // never triggered heads-up or woke the screen.
                        val senderName = userRepository.getUserByIdOnce(event.senderId)
                            ?.deviceName ?: "A contact"
                        notificationHelper.showIncomingTransferNotification(
                            transferId  = event.transferId,
                            senderName  = senderName,
                            totalSize   = event.totalSize,
                            fileCount   = event.files.size
                        )
                    }

                    else -> { }
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, IPPhoneApp.CHANNEL_FILE_TRANSFER)
            .setContentTitle("File Transfer")
            .setContentText("Transfer in progress…")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // Android drops/rate-limits notifications posted faster than ~1/s, so update at most once a second.
    private val lastNotificationUpdate = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun updateProgressNotification(transferId: String, transferred: Long, total: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastNotificationUpdate[transferId] ?: 0L
        if (now - last < 1000L && transferred < total) return
        lastNotificationUpdate[transferId] = now

        val progress = if (total > 0) ((transferred.toFloat() / total) * 100).toInt() else 0
        val stats = statsTracker.stats.value[transferId]
        val details = if (stats != null && stats.speedBps > 0) {
            val eta = if (stats.etaSeconds >= 0) " · ETA ${(stats.etaSeconds * 1000).toFormattedDuration()}" else ""
            " · ${stats.speedBps.toTransferSpeed()}$eta"
        } else ""

        val cancelIntent = Intent(this, FileTransferService::class.java).apply {
            action = ACTION_CANCEL_TRANSFER
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val cancelPi = PendingIntent.getService(
            this, transferId.hashCode(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, IPPhoneApp.CHANNEL_FILE_TRANSFER)
            .setContentTitle("Transferring files")
            .setContentText("${transferred.toFormattedSize()} / ${total.toFormattedSize()}$details")
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(100, progress, false)
            .addAction(R.drawable.ic_notification, "Cancel", cancelPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(Constants.NOTIFICATION_ID_TRANSFER, notification)
    }

    private fun showCompletedNotification(transferId: String) {
        val notification = NotificationCompat.Builder(this, IPPhoneApp.CHANNEL_FILE_TRANSFER)
            .setContentTitle("Transfer complete")
            .setContentText("Files transferred successfully")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(transferId.hashCode(), notification)
    }

    private fun checkAndStopSelf() {
        if (activeTransferCount <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}

