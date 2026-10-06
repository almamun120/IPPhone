package com.ipphone.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.ipphone.MainActivity
import com.ipphone.R
import com.ipphone.IPPhoneApp
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.network.calling.CallManager
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.discovery.DiscoveryManager
import com.ipphone.network.messaging.MessagingEngine
import com.ipphone.network.presence.PresenceManager
import com.ipphone.utils.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class NetworkService : Service() {

    @Inject lateinit var discoveryManager: DiscoveryManager
    @Inject lateinit var presenceManager: PresenceManager
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var callManager: CallManager
    @Inject lateinit var messagingEngine: MessagingEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var isRestarting = false

    // Keep the Wi-Fi radio awake and let it receive UDP broadcast/multicast with the screen off,
    // otherwise heartbeats, discovery and incoming calls are missed on many phones.
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("NetworkService created")
        startForeground(Constants.NOTIFICATION_ID_NETWORK, createNotification())
        acquireWifiLocks()
        requestBatteryOptimisationExemption()
        initializeNetworking()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("NetworkService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("NetworkService destroyed")
        unregisterNetworkCallback()
        releaseWifiLocks()
        cleanup()
    }

    // ── Wi-Fi / multicast locks ──────────────────────────────────────────────

    private fun acquireWifiLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "IPPhone:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("IPPhone:multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            Timber.d("Wi-Fi + multicast locks acquired")
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire Wi-Fi locks")
        }
    }

    private fun releaseWifiLocks() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifiLock = null
        multicastLock = null
    }

    // ── Networking init / cleanup ────────────────────────────────────────────

    private fun initializeNetworking() {
        serviceScope.launch {
            val deviceId    = preferencesManager.getDeviceUuid()
            val deviceName  = preferencesManager.getDeviceName()
            val profileImage = preferencesManager.getProfileImage()

            val tcpPort = connectionManager.start(Constants.TCP_PORT)
            Timber.d("TCP Server bound on port $tcpPort")

            // Pass the actual bound port to DiscoveryManager so subnet scans
            // advertise the correct port in their HeartbeatMessage payloads.
            discoveryManager.setTcpPort(tcpPort)

            discoveryManager.registerService(deviceId, deviceName, profileImage, tcpPort)
            presenceManager.start(tcpPort)

            Timber.d("Networking stack initialised (port=$tcpPort)")
        }
    }

    private fun cleanup() {
        presenceManager.stop()
        connectionManager.stop()
        discoveryManager.cleanup()
    }

    // ── Battery optimisation exemption ───────────────────────────────────────

    private fun requestBatteryOptimisationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to request battery optimisation exemption")
        }
    }

    // ── Network change callback ──────────────────────────────────────────────

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.d("Network available — scheduling stack restart")
                scheduleRestart()
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Timber.d("Link properties changed — scheduling stack restart")
                scheduleRestart()
            }
            override fun onLost(network: Network) {
                Timber.d("Network lost")
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Timber.e(e, "Failed to register NetworkCallback")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun scheduleRestart() {
        if (isRestarting) {
            Timber.d("scheduleRestart: already in progress — ignoring")
            return
        }
        serviceScope.launch {
            isRestarting = true
            try {
                Timber.d("Restarting network stack after connectivity change…")
                cleanup()
                delay(800)
                initializeNetworking()
                Timber.d("Network stack restarted successfully")
            } catch (e: Exception) {
                Timber.e(e, "Error during network stack restart")
            } finally {
                isRestarting = false
            }
        }
    }

    // ── Foreground notification ──────────────────────────────────────────────

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, IPPhoneApp.CHANNEL_NETWORK_SERVICE)
            .setContentTitle("WiFi Calling")
            .setContentText("Connected and listening for nearby devices")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}