package com.ipphone.service

import android.media.Ringtone
import android.media.RingtoneManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ipphone.MainActivity
import com.ipphone.R
import com.ipphone.IPPhoneApp
import com.ipphone.domain.model.CallType
import com.ipphone.network.calling.CallManager
import com.ipphone.utils.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class CallService : Service() {

    @Inject
    lateinit var callManager: CallManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    // FIX: Flags to prevent duplicate operations
    @Volatile private var isRinging = false
    @Volatile private var isServiceStopping = false

    companion object {
        const val ACTION_OUTGOING_CALL_FOREGROUND = "com.ipphone.OUTGOING_CALL_FOREGROUND"
        const val ACTION_INCOMING_CALL_RING = "com.ipphone.INCOMING_CALL_RING"
        const val ACTION_ACCEPT_CALL = "com.ipphone.ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "com.ipphone.REJECT_CALL"
        const val ACTION_END_CALL = "com.ipphone.END_CALL"

        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_CALL_TYPE = "call_type"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_CALL_ID = "call_id"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("CallService created")
        observeCallState()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (isServiceStopping) {
            Timber.d("Service is stopping, skipping foreground notification")
            return
        }

        try {
            val type = calculateForegroundServiceType()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
                startForeground(Constants.NOTIFICATION_ID_CALL, notification, type)
            } else {
                startForeground(Constants.NOTIFICATION_ID_CALL, notification)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start foreground service")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stopSelfSafely()
            }
        }
    }

    private fun calculateForegroundServiceType(): Int {
        var type = 0
        val state = callManager.callState.value

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }

        val canUseMedia = state !is CallManager.CallState.Idle &&
                state !is CallManager.CallState.Ended &&
                state !is CallManager.CallState.Incoming

        if (canUseMedia) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
        }

        return type
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OUTGOING_CALL_FOREGROUND -> {
                isServiceStopping = false
                startForegroundWithNotification(createOutgoingCallNotification())
            }

            ACTION_INCOMING_CALL_RING -> {
                isServiceStopping = false
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Unknown"
                val callType = intent.getStringExtra(EXTRA_CALL_TYPE)
                    ?.let { CallType.valueOf(it) } ?: CallType.AUDIO

                startForegroundWithNotification(
                    createIncomingCallNotification(callId, peerName, callType)
                )
                startRinging()

                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("incoming_call", true)
                    putExtra(EXTRA_CALL_ID, callId)
                }
                startActivity(activityIntent)
            }

            ACTION_ACCEPT_CALL -> {
                stopRinging()
                serviceScope.launch { callManager.acceptCall() }
            }

            ACTION_REJECT_CALL -> {
                stopRinging()
                serviceScope.launch {
                    callManager.rejectCall()
                    stopSelfSafely()
                }
            }

            ACTION_END_CALL -> {
                stopRinging()
                serviceScope.launch {
                    callManager.endCall()
                    stopSelfSafely()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeCallState() {
        stateObserverJob?.cancel()

        stateObserverJob = serviceScope.launch {
            // Use a flag to skip the initial state if it's Idle
            var isFirstState = true

            callManager.callState.collect { state ->
                Timber.d("CallService observed state: $state")
                
                if (isFirstState) {
                    isFirstState = false
                    if (state is CallManager.CallState.Idle) {
                        return@collect
                    }
                }

                if (isServiceStopping) return@collect

                when (state) {
                    is CallManager.CallState.Connected -> {
                        stopRinging()
                        updateNotification(createOngoingCallNotification(state.callType))
                    }

                    is CallManager.CallState.Connecting -> {
                        updateNotification(createOutgoingCallNotification())
                    }

                    is CallManager.CallState.Ended,
                    CallManager.CallState.Idle -> {
                        stopRinging()
                        stopSelfSafely()
                    }

                    else -> { }
                }
            }
        }
    }

    private var ringtone: Ringtone? = null

    private fun startRinging() {
        if (isRinging) {
            Timber.d("Already ringing, skipping")
            return
        }

        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            ringtone = RingtoneManager.getRingtone(this, ringtoneUri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
            isRinging = true
            Timber.d("Started ringing using RingtoneManager (looping=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.P})")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start ringtone")
            isRinging = false
        }
    }

    private fun stopRinging() {
        if (!isRinging && ringtone == null) {
            return
        }

        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            ringtone = null
            isRinging = false
            Timber.d("Stopped ringing")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping ringtone")
            isRinging = false
            ringtone = null
        }
    }

    private fun createIncomingCallNotification(
        callId: String,
        peerName: String,
        callType: CallType
    ): Notification {
        val acceptIntent = Intent(this, CallService::class.java).apply {
            action = ACTION_ACCEPT_CALL
        }
        val acceptPendingIntent = PendingIntent.getService(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(this, CallService::class.java).apply {
            action = ACTION_REJECT_CALL
        }
        val rejectPendingIntent = PendingIntent.getService(
            this, 2, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call", true)
            putExtra(EXTRA_CALL_ID, callId)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTypeText = if (callType == CallType.VIDEO) "video call" else "call"

        return NotificationCompat.Builder(this, IPPhoneApp.CHANNEL_INCOMING_CALLS)
            .setContentTitle("Incoming $callTypeText")
            .setContentText("$peerName is calling")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSound(null)
            .setVibrate(longArrayOf(0))
            .setDefaults(0)
            .addAction(R.drawable.ic_notification, "Decline", rejectPendingIntent)
            .addAction(R.drawable.ic_notification, "Answer", acceptPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .build()
    }

    private fun createOutgoingCallNotification(): Notification {
        val endIntent = Intent(this, CallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPendingIntent = PendingIntent.getService(
            this, 3, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, IPPhoneApp.CHANNEL_CALLS)
            .setContentTitle("Calling...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(R.drawable.ic_notification, "Cancel", endPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createOngoingCallNotification(callType: CallType): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(this, CallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPendingIntent = PendingIntent.getService(
            this, 3, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTypeText = if (callType == CallType.VIDEO) "Video call" else "Audio call"

        return NotificationCompat.Builder(this, IPPhoneApp.CHANNEL_CALLS)
            .setContentTitle("$callTypeText in progress")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_notification, "End", endPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        if (!isServiceStopping) {
            startForegroundWithNotification(notification)
        }
    }

    // FIX: Safe stop that prevents duplicate calls
    private fun stopSelfSafely() {
        if (isServiceStopping) {
            Timber.d("Already stopping service")
            return
        }

        isServiceStopping = true
        stateObserverJob?.cancel()
        stopRinging()

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Timber.d("Service stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping service")
        }
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        stopRinging()
        isServiceStopping = true
        super.onDestroy()
        Timber.d("CallService destroyed")
    }
}

