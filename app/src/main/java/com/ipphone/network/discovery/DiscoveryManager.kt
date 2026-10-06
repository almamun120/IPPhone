package com.ipphone.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.domain.model.DeviceInfo
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.protocol.HeartbeatMessage
import com.ipphone.utils.Constants
import com.ipphone.utils.getBestLocalIpAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userDao: UserDao,
    private val connectionManager: ConnectionManager
) {
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveryProgress = MutableStateFlow(0f)
    val discoveryProgress: StateFlow<Float> = _discoveryProgress.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredServiceName: String? = null

    private val resolveQueue   = LinkedBlockingQueue<NsdServiceInfo>()
    private val isResolving    = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var subnetScanJob: Job? = null

    // ── NSD registration ──────────────────────────────────────────────────────

    data class DiscoveredDevice(
        val serviceName: String,
        val serviceType: String,
        val host: InetAddress?,
        val port: Int,
        val deviceId: String?,
        val deviceName: String?,
        val profileImage: String? = null,
        val resolvedAt: Long = System.currentTimeMillis()
    )

    fun registerService(deviceId: String, deviceName: String, profileImage: String?, port: Int) {
        if (_isRegistered.value) {
            Timber.d("Service already registered")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${Constants.SERVICE_NAME}_$deviceId"
            serviceType = Constants.SERVICE_TYPE
            setPort(port)
            setAttribute("deviceId", deviceId)
            val safeDeviceName = if (deviceName.length > 32) deviceName.take(32) else deviceName
            setAttribute("deviceName", safeDeviceName)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                _isRegistered.value = true
                Timber.d("NSD service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _isRegistered.value = false
                Timber.e("NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _isRegistered.value = false
                Timber.d("NSD service unregistered: ${info.serviceName}")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Timber.e(e, "Failed to register NSD service")
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (e: Exception) {
                Timber.e(e, "Failed to unregister NSD service")
            }
        }
        registrationListener = null
        _isRegistered.value  = false
    }

    // ── Discovery (NSD + subnet scan fallback) ────────────────────────────────

    /**
     * Starts discovery using two parallel strategies:
     *
     *  1. NSD (mDNS) — works on home routers and hotspots.
     *     Fails silently on enterprise/office routers that block multicast DNS.
     *
     *  2. Subnet UDP scan — sends a unicast UDP heartbeat to every host in the
     *     local /24 subnet on port Constants.UDP_PORT. Any device running
     *     IPPhone will reply with its own heartbeat, which our UDP listener
     *     picks up and adds to discoveredDevices. This works even when
     *     AP isolation blocks broadcast/multicast because each unicast packet
     *     is individually routed by the AP.
     *
     *  3. DB-known peer TCP ping — for peers that were seen before (stored in
     *     UserDao with an IP), we attempt a TCP connection so they appear
     *     online even if NSD is unavailable.
     */
    fun startDiscovery(durationMs: Long = 10000L) {
        if (_isDiscovering.value) {
            Timber.d("Discovery already running")
            return
        }
        _isDiscovering.value = true
        _discoveryProgress.value = 0f

        // Strategy 1: NSD (mDNS) — best-effort, will silently fail on AP isolation
        startNsdDiscovery()

        // Strategy 2: Subnet UDP scan — works when NSD fails
        startSubnetScan()

        // Strategy 3: Ping known DB peers via TCP
        pingKnownDbPeers()

        // Progress timer
        progressJob?.cancel()
        progressJob = scope.launch {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < durationMs) {
                val p = (System.currentTimeMillis() - start).toFloat() / durationMs
                _discoveryProgress.value = p.coerceIn(0f, 1f)
                delay(100)
            }
            _discoveryProgress.value = 1f
            stopDiscovery()
        }
    }

    private fun startNsdDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.d("NSD discovery started for: $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Timber.d("NSD discovery stopped")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Timber.d("NSD service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName == registeredServiceName) return
                resolveService(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Timber.d("NSD service lost: ${serviceInfo.serviceName}")
                _discoveredDevices.update { current ->
                    current.filterValues { it.serviceName != serviceInfo.serviceName }
                }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // This is expected on AP-isolated networks — the subnet scan will handle it
                Timber.w("NSD discovery failed (errorCode=$errorCode) — AP isolation likely; subnet scan active")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("NSD stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                Constants.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to start NSD discovery — using subnet scan only")
        }
    }

    /**
     * Subnet UDP scanner.
     *
     * Sends a HeartbeatMessage as a unicast UDP datagram to every x.x.x.1–254
     * address in our subnet. On AP-isolated networks, each packet travels via
     * the router's L3 forwarding (not broadcast) so it reaches the target even
     * when inter-client broadcast is blocked.
     *
     * Receiving device's PresenceManager.listenerSocket picks it up and
     * replies with its own broadcast/unicast heartbeat (which we receive),
     * marking the peer as discovered.
     *
     * We spread the 254 packets over 3 seconds to avoid flooding the network.
     */
    private fun startSubnetScan() {
        subnetScanJob?.cancel()
        subnetScanJob = scope.launch {
            val localIp = getBestLocalIpAddress() ?: run {
                Timber.w("SubnetScan: no local IP — skipping")
                return@launch
            }

            val parts = localIp.split(".")
            if (parts.size != 4) {
                Timber.w("SubnetScan: invalid IP $localIp")
                return@launch
            }
            val subnet = "${parts[0]}.${parts[1]}.${parts[2]}"

            val myId   = preferencesManager().getDeviceUuid()
            val myName = preferencesManager().getDeviceName()
            val heartbeat = HeartbeatMessage(
                senderId   = myId,
                deviceName = myName,
                tcpPort    = currentTcpPort
            )
            val json  = com.ipphone.network.protocol.protocolJson
                .encodeToString(HeartbeatMessage.serializer(), heartbeat)
            val bytes = json.toByteArray(Charsets.UTF_8)

            Timber.d("SubnetScan: scanning $subnet.1–254 on UDP ${Constants.UDP_PORT}")

            val socket = try { DatagramSocket() } catch (e: Exception) {
                Timber.e(e, "SubnetScan: cannot open socket"); return@launch
            }

            socket.use {
                for (host in 1..254) {
                    if (!_isDiscovering.value) break
                    val targetIp = "$subnet.$host"
                    if (targetIp == localIp) continue
                    try {
                        socket.send(
                            DatagramPacket(
                                bytes, bytes.size,
                                InetAddress.getByName(targetIp),
                                Constants.UDP_PORT
                            )
                        )
                    } catch (_: Exception) {}
                    // ~12 ms per host → 254 hosts ≈ 3 s total
                    if (host % 10 == 0) delay(120)
                }
            }
            Timber.d("SubnetScan: scan complete for $subnet.x")
        }
    }

    /**
     * Pings peers already stored in UserDao (from previous sessions) via TCP.
     * This makes them appear online immediately without waiting for them to
     * respond to a broadcast/unicast heartbeat.
     */
    private fun pingKnownDbPeers() {
        scope.launch {
            delay(500) // Let the TCP server start first
            try {
                val users = withContext(Dispatchers.IO) { userDao.getAllKnownUsers() }
                Timber.d("PingKnownPeers: pinging ${users.size} known peer(s)")
                for (user in users) {
                    val ip = user.ipAddress ?: continue
                    if (ip.isBlank()) continue
                    val deviceId = user.uuid
                    val port = if (user.tcpPort > 0) user.tcpPort else Constants.TCP_PORT

                    // Attempt TCP connection — if successful, ConnectionManager
                    // registers the peer and incomingMessages fires a HeartbeatMessage
                    // which MessagingEngine processes → marks peer online.
                    scope.launch {
                        try {
                            val sent = connectionManager.sendMessage(
                                deviceId,
                                HeartbeatMessage(
                                    senderId   = preferencesManager().getDeviceUuid(),
                                    deviceName = preferencesManager().getDeviceName(),
                                    tcpPort    = currentTcpPort
                                )
                            )
                            if (sent) {
                                Timber.d("PingKnownPeers: TCP ping OK → $deviceId @ $ip:$port")
                                // Add to discovered devices so NearbyScreen shows it
                                val device = DiscoveredDevice(
                                    serviceName = "db_peer_$deviceId",
                                    serviceType = Constants.SERVICE_TYPE,
                                    host        = InetAddress.getByName(ip),
                                    port        = port,
                                    deviceId    = deviceId,
                                    deviceName  = user.deviceName,
                                    profileImage = user.profileImage
                                )
                                _discoveredDevices.update { it + (deviceId to device) }
                            }
                        } catch (e: Exception) {
                            Timber.v("PingKnownPeers: $deviceId unreachable — ${e.message}")
                        }
                    }
                    delay(100) // stagger pings
                }
            } catch (e: Exception) {
                Timber.e(e, "pingKnownDbPeers failed")
            }
        }
    }

    // Lazy accessor for PreferencesManager (needed without Hilt injection cycle)
    private var _prefsManager: com.ipphone.data.local.datastore.PreferencesManager? = null
    @Volatile private var currentTcpPort: Int = Constants.TCP_PORT

    fun setTcpPort(port: Int) { currentTcpPort = port }

    private fun preferencesManager(): com.ipphone.data.local.datastore.PreferencesManager {
        return _prefsManager ?: throw IllegalStateException("PreferencesManager not set")
    }

    fun setPreferencesManager(pm: com.ipphone.data.local.datastore.PreferencesManager) {
        _prefsManager = pm
    }

    // ── Stop ─────────────────────────────────────────────────────────────────

    fun stopDiscovery() {
        progressJob?.cancel(); progressJob = null
        subnetScanJob?.cancel(); subnetScanJob = null
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) {
                Timber.e(e, "Failed to stop NSD discovery")
            }
        }
        discoveryListener    = null
        _isDiscovering.value = false
    }

    // ── NSD resolve queue ─────────────────────────────────────────────────────

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        resolveQueue.offer(serviceInfo)
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) { isResolving.set(false); return }

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD resolve failed for ${serviceInfo.serviceName}: $errorCode")
                isResolving.set(false); drainResolveQueue()
            }
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val deviceId   = resolvedInfo.attributes["deviceId"]?.let { String(it) }
                val deviceName = resolvedInfo.attributes["deviceName"]?.let { String(it) }

                if (deviceId == null) {
                    Timber.w("NSD resolved service has no deviceId; skipping")
                    isResolving.set(false); drainResolveQueue(); return
                }

                val device = DiscoveredDevice(
                    serviceName  = resolvedInfo.serviceName,
                    serviceType  = resolvedInfo.serviceType,
                    host         = resolvedInfo.host,
                    port         = resolvedInfo.port,
                    deviceId     = deviceId,
                    deviceName   = deviceName,
                    profileImage = null
                )
                _discoveredDevices.update { it + (deviceId to device) }
                Timber.d("NSD resolved: $deviceId @ ${resolvedInfo.host}:${resolvedInfo.port}")

                isResolving.set(false); drainResolveQueue()
            }
        }

        try {
            nsdManager.resolveService(next, listener)
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve NSD service: ${next.serviceName}")
            isResolving.set(false); drainResolveQueue()
        }
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyMap()
    }

    fun cleanup() {
        stopDiscovery()
        unregisterService()
        clearDiscoveredDevices()
        resolveQueue.clear()
    }
}