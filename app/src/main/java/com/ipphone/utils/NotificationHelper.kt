package com.ipphone.utils

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.ipphone.MainActivity
import com.ipphone.R
import com.ipphone.IPPhoneApp
import com.ipphone.domain.model.Message
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ── Chat message notification ─────────────────────────────

    fun showMessageNotification(
        senderId: String,
        senderName: String,
        message: Message
    ) {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_chat", senderId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, senderId.hashCode(), contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val person = Person.Builder().setName(senderName).build()

        val notification = NotificationCompat.Builder(context, IPPhoneApp.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(
                NotificationCompat.MessagingStyle(person)
                    .addMessage(message.content ?: "", message.createdAt, person)
            )
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(senderId.hashCode(), notification)
    }

    // ── Incoming file / folder transfer notification ──────────

    fun showIncomingTransferNotification(
        transferId: String,
        senderName: String,
        totalSize: Long,
        fileCount: Int
    ) {
        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("accept_transfer", transferId)
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            context, transferId.hashCode(), acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, transferId.hashCode() + 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, com.ipphone.service.FileTransferService::class.java).apply {
            action = com.ipphone.service.FileTransferService.ACTION_CANCEL_TRANSFER
            putExtra(com.ipphone.service.FileTransferService.EXTRA_TRANSFER_ID, transferId)
        }
        val declinePendingIntent = PendingIntent.getService(
            context, transferId.hashCode() + 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sizeText = android.text.format.Formatter.formatShortFileSize(context, totalSize)
        val desc = "$senderName wants to send you $fileCount file(s) · $sizeText"

        val notification = NotificationCompat.Builder(context, IPPhoneApp.CHANNEL_TRANSFER_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Incoming Transfer")
            .setContentText(desc)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_notification, "Decline", declinePendingIntent)
            .addAction(R.drawable.ic_notification, "Accept",  acceptPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(transferId.hashCode(), notification)
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
}
