package com.ipphone

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import com.ipphone.utils.AppDiagnostics
import timber.log.Timber

@HiltAndroidApp
class IPPhoneApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        createNotificationChannels()

        // Remember crashes so the Diagnostics screen can explain unexpected restarts.
        AppDiagnostics.onProcessStart(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { AppDiagnostics.recordCrash(this, thread, error) } catch (_: Throwable) {}
            previousHandler?.uncaughtException(thread, error)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Background service — silent persistent notification
            val networkChannel = NotificationChannel(
                CHANNEL_NETWORK_SERVICE, "Network Service", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps IP Phone connected"; setShowBadge(false) }

            // File transfer progress — silent updates
            val transferChannel = NotificationChannel(
                CHANNEL_FILE_TRANSFER, "File Transfer Progress", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows file transfer progress"; setShowBadge(true) }

            // High-priority channel for incoming transfer alerts.
            val transferIncomingChannel = NotificationChannel(
                CHANNEL_TRANSFER_INCOMING, "Incoming Transfers", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when another device wants to share files"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // Ongoing call — silent
            val callChannel = NotificationChannel(
                CHANNEL_CALLS, "Ongoing Calls", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Calls in progress"; setShowBadge(false); setSound(null, null) }

            // Incoming call — vibrate + wakes screen
            val incomingCallChannel = NotificationChannel(
                CHANNEL_INCOMING_CALLS, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call notifications"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // Chat messages
            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "New message notifications"; setShowBadge(true); enableVibration(true) }

            nm.createNotificationChannels(
                listOf(
                    networkChannel,
                    transferChannel,
                    transferIncomingChannel,
                    callChannel,
                    incomingCallChannel,
                    messageChannel
                )
            )
        }
    }

    companion object {
        const val CHANNEL_NETWORK_SERVICE   = "network_service"
        const val CHANNEL_FILE_TRANSFER     = "file_transfer"
        const val CHANNEL_TRANSFER_INCOMING = "transfer_incoming_v1"
        const val CHANNEL_CALLS             = "calls_ongoing"
        const val CHANNEL_INCOMING_CALLS    = "incoming_calls_v111"
        const val CHANNEL_MESSAGES          = "messages"
    }
}
