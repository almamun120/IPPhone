package com.ipphone.network.presence

import android.content.Context
import android.net.wifi.WifiManager
import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.data.local.database.entity.UserEntity
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.protocol.HeartbeatMessage
import com.ipphone.utils.Constants
import com.ipphone.utils.getBestLocalIpAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresenceManager @Inject constructor(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val userDao: UserDao,
    private val connectionManager: ConnectionManager
) {
    private val scope              = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _onlineDevices = MutableStateFlow<Set<String>>(emptySet())
    val onlineDevices: StateFlow<Set<String>> = _onlineDevices.asStateFlow()

    private var listenerSocket: DatagramSocket? = null
    private var senderSocket: DatagramSocket?   = null

    private var heartbeatJob: Job? = null
    private var listenerJob: Job?  = null
    private var cleanupJob: Job?   = null
    private var unicastJob: Job?   = null
    private var tcpObserverJob: Job? = null

    @Volatile private var tcpPort: Int = Constants.TCP_PORT

    private val lastSeenTimestamps = mutableMapOf<String, Long>()
    private val lastKnownIp        = mutableMapOf<String, String>()
    private val lastTcpAttempt     = mutableMapOf<String, Long>()

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    fun start(tcpPort: Int) {
        stop()
        this.tcpPort = tcpPort
        try {
            listenerSocket = DatagramSocket(null).apply {
                reuseAddress      = true
                soTimeout         = 10_000
                receiveBufferSize = 64 * 1024
                bind(InetSocketAddress(Constants.UDP_PORT))
            }
            senderSocket = DatagramSocket()

            startHeartbeatBroadcaster()
            startHeartbeatListener()
            startCleanupJob()
            startUnicastHeartbeat()
            startTcpObserver()

            val localIp   = getLocalIpAddress()
            val isHotspot = isHotspotHost()
            Timber.d("PresenceManager started | UDP=${Constants.UDP_PORT} TCP=$tcpPort localIP=$localIp hotspot=$isHotspot")
        } catch (e: Exception) {
            Timber.e(e, "PresenceManager start failed")
        }
    }

    fun stop() {
        heartbeatJob?.cancel(); listenerJob?.cancel(); cleanupJob?.cancel(); unicastJob?.cancel()
        tcpObserverJob?.cancel()
        heartbeatJob = null; listenerJob = null; cleanupJob = null; unicastJob = null
        tcpObserverJob = null
        try { listenerSocket?.close() } catch (_: Exception) {}
        try { senderSocket?.close()   } catch (_: Exception) {}
        listenerSocket = null; senderSocket = null
        _onlineDevices.value = emptySet()
        lastSeenTimestamps.clear(); lastKnownIp.clear(); lastTcpAttempt.clear()
        Timber.d("PresenceManager stopped")
    }

    private fun startTcpObserver() {
        tcpObserverJob = scope.launch {
            connectionManager.incomingMessages.collect { (senderId, message) ->
                // Any message received over TCP is a strong presence signal
                val now = System.currentTimeMillis()
                lastSeenTimestamps[senderId] = now
                
                if (!_onlineDevices.value.contains(senderId)) {
                    _onlineDevices.update { it + senderId }
                    Timber.d("Device $senderId online via TCP ${message.type}")
                }
            }
        }
    }

    fun isDeviceOnline(deviceId: String): Boolean = _onlineDevices.value.contains(deviceId)

    // =========================================================================
    //  Network topology helpers
    // =========================================================================

    private fun isHotspotHost(): Boolean {
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if ((wm.connectionInfo?.ipAddress ?: 0) != 0) return false
            val prefixes = listOf("wlan", "ap", "swlan", "softap")
            val ifaces   = Collections.list(NetworkInterface.getNetworkInterfaces()) ?: return false
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (!prefixes.any { name.startsWith(it) }) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.isNotEmpty()) return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Returns all IPv4 broadcast addresses for active interfaces.
     * Falls back to 255.255.255.255 if none found.
     *
     * NOTE: On AP-isolated office routers the broadcast packets are dropped
     * by the AP before reaching other clients. That is why we also send
     * unicast heartbeats to known peers (see startUnicastHeartbeat).
     */
    private fun getAllBroadcastAddresses(): List<String> {
        val results = mutableListOf<String>()
        try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces()) ?: emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val broadcast = ia.broadcast ?: continue
                    val bs = broadcast.hostAddress ?: continue
                    if (bs !in results) results.add(bs)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "getAllBroadcastAddresses failed")
        }
        if (results.isEmpty()) results.add("255.255.255.255")
        return results
    }

    fun getLocalIpAddress(): String? = getBestLocalIpAddress()

    // =========================================================================
    //  Heartbeat broadcaster (broadcast — works on home/hotspot, may fail in office)
    // =========================================================================

    private fun startHeartbeatBroadcaster() {
        heartbeatJob = scope.launch {
            while (isActive) {
                try { broadcastHeartbeat() }
                catch (e: Exception) { Timber.e(e, "Broadcast heartbeat error") }
                delay(Constants.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun broadcastHeartbeat() {
        val socket = senderSocket ?: return
        val bytes  = buildHeartbeatBytes() ?: return
        val targets = getAllBroadcastAddresses()
        for (addr in targets) {
            try {
                socket.send(
                    DatagramPacket(bytes, bytes.size, InetAddress.getByName(addr), Constants.UDP_PORT)
                )
                Timber.v("Broadcast heartbeat → $addr")
            } catch (e: Exception) {
                Timber.w("Broadcast failed on $addr: ${e.message}")
            }
        }
    }

    // =========================================================================
    //  NEW: Unicast heartbeat to known peers (fixes AP-isolated office routers)
    //
    //  When AP isolation is active, broadcast UDP packets are dropped by the
    //  access point before they reach other clients. However, directed unicast
    //  UDP packets (sent to a specific IP) ARE routed correctly because they
    //  travel through the AP's normal L3 routing path.
    //
    //  Strategy:
    //    1. Every HEARTBEAT_INTERVAL_MS we look up all known users in the DB
    //       that have a stored IP address.
    //    2. We send a unicast UDP heartbeat directly to each IP:UDP_PORT.
    //    3. If UDP is also blocked (some enterprise firewalls block all UDP),
    //       we fall back to a TCP "ping" — we open a short-lived TCP connection
    //       to the peer's TCP port and send our HeartbeatMessage, then rely on
    //       ConnectionManager's normal handshake to record the response.
    // =========================================================================

    private fun startUnicastHeartbeat() {
        unicastJob = scope.launch {
            // Initial delay so the DB has time to populate
            delay(3_000)
            while (isActive) {
                try { sendUnicastHeartbeats() }
                catch (e: Exception) { Timber.e(e, "Unicast heartbeat error") }
                delay(Constants.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendUnicastHeartbeats() {
        val knownUsers = withContext(Dispatchers.IO) {
            try {
                // Heartbeat users seen in the last 7 days to keep the list relevant
                val weekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                userDao.getAllKnownUsers().filter { (it.lastSeen ?: 0) > weekAgo }
            } catch (e: Exception) {
                Timber.e(e, "getUsersWithIp failed"); emptyList()
            }
        }
        if (knownUsers.isEmpty()) return

        val bytes  = buildHeartbeatBytes() ?: return
        val socket = senderSocket
        val now    = System.currentTimeMillis()

        for (user in knownUsers) {
            val ip = user.ipAddress ?: continue
            if (ip.isBlank()) continue

            // 1. Try unicast UDP first (cheap, stateless)
            if (socket != null && !socket.isClosed) {
                try {
                    socket.send(
                        DatagramPacket(
                            bytes, bytes.size,
                            InetAddress.getByName(ip),
                            Constants.UDP_PORT
                        )
                    )
                    Timber.v("Unicast UDP heartbeat → $ip:${Constants.UDP_PORT}")
                } catch (e: Exception) {
                    Timber.v("Unicast UDP failed → $ip: ${e.message}")
                }
            }

            // 2. TCP Fallback (for AP isolation where UDP might be blocked)
            // Throttled to once every 30s per device to avoid hammering offline peers.
            val lastTcp = lastTcpAttempt[user.uuid] ?: 0L
            val shouldTryTcp = !_onlineDevices.value.contains(user.uuid) && (now - lastTcp > 30_000L)

            if (shouldTryTcp) {
                lastTcpAttempt[user.uuid] = now
                val port = if (user.tcpPort > 0) user.tcpPort else Constants.TCP_PORT
                sendTcpHeartbeat(user.uuid, ip, port)
            }

            // Stagger unicast packets to avoid network bursts
            delay(20)
        }
    }

    /**
     * Opens a TCP connection to the peer (or reuses an existing one via
     * ConnectionManager) and sends a HeartbeatMessage. The peer's
     * ConnectionManager will receive it and dispatch it to MessagingEngine
     * which calls handleHeartbeat → updates last-seen.
     *
     * On our side, ConnectionManager.readFromConnection will receive the
     * peer's handshake HeartbeatMessage and emit it on incomingMessages,
     * which MessagingEngine then handles, causing us to mark the peer online.
     */
    private suspend fun sendTcpHeartbeat(deviceId: String, ip: String, port: Int) {
        try {
            val sent = connectionManager.sendMessage(
                deviceId,
                HeartbeatMessage(
                    senderId   = preferencesManager.getDeviceUuid(),
                    deviceName = preferencesManager.getDeviceName(),
                    tcpPort    = tcpPort
                )
            )
            if (sent) {
                // Mark online immediately — TCP delivery is a reliable presence signal
                lastSeenTimestamps[deviceId] = System.currentTimeMillis()
                lastKnownIp[deviceId] = ip
                _onlineDevices.update { it + deviceId }
                Timber.d("TCP heartbeat delivered → $deviceId @ $ip:$port")
            } else {
                Timber.v("TCP heartbeat failed → $deviceId @ $ip:$port")
            }
        } catch (e: Exception) {
            Timber.v("TCP heartbeat exception → $deviceId: ${e.message}")
        }
    }

    private suspend fun buildHeartbeatBytes(): ByteArray? {
        return try {
            val myId   = preferencesManager.getDeviceUuid()
            val myName = preferencesManager.getDeviceName()
            val msg    = HeartbeatMessage(senderId = myId, deviceName = myName, tcpPort = tcpPort)
            val json   = com.ipphone.network.protocol.protocolJson
                .encodeToString(HeartbeatMessage.serializer(), msg)
            json.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "buildHeartbeatBytes failed"); null
        }
    }

    // =========================================================================
    //  Heartbeat listener (receives both broadcast and unicast UDP)
    // =========================================================================

    private fun startHeartbeatListener() {
        listenerJob = scope.launch(listenerDispatcher) {
            val buf = ByteArray(4096)
            while (isActive) {
                try {
                    val socket = listenerSocket ?: break
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)

                    val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val heartbeat = try {
                        com.ipphone.network.protocol.protocolJson
                            .decodeFromString<HeartbeatMessage>(json)
                    } catch (_: Exception) { continue }

                    val senderIp = packet.address.hostAddress ?: continue
                    handleHeartbeat(heartbeat, senderIp)

                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (isActive) Timber.e(e, "UDP receive error")
                }
            }
        }
    }

    private suspend fun handleHeartbeat(heartbeat: HeartbeatMessage, senderIp: String) {
        val deviceId = heartbeat.senderId
        val myId     = preferencesManager.getDeviceUuid()
        if (deviceId == myId) return

        lastSeenTimestamps[deviceId] = System.currentTimeMillis()

        val dbUser     = userDao.getUserById(deviceId)
        val storedIp   = dbUser?.ipAddress
        val storedPort = dbUser?.tcpPort ?: 0

        val ipChanged   = storedIp != null && storedIp != senderIp
        val portChanged = storedPort > 0   && storedPort != heartbeat.tcpPort

        if (ipChanged || portChanged) {
            Timber.d("IP/port changed for $deviceId: old=$storedIp:$storedPort new=$senderIp:${heartbeat.tcpPort} — closing stale connection")
            connectionManager.closeConnection(deviceId)
        }

        if (dbUser != null) {
            userDao.updatePresence(deviceId, senderIp, heartbeat.tcpPort)
        } else {
            userDao.insert(
                UserEntity(
                    uuid         = deviceId,
                    deviceName   = heartbeat.deviceName,
                    profileImage = null,
                    ipAddress    = senderIp,
                    tcpPort      = heartbeat.tcpPort,
                    pairingState = "NONE",
                    isBlocked    = false,
                    blockedAt    = null,
                    pairedAt     = null,
                    lastSeen     = System.currentTimeMillis(),
                    createdAt    = System.currentTimeMillis(),
                    updatedAt    = System.currentTimeMillis()
                )
            )
            Timber.d("New user from heartbeat: $deviceId @ $senderIp:${heartbeat.tcpPort}")
        }

        lastKnownIp[deviceId] = senderIp
        _onlineDevices.update { it + deviceId }
        Timber.v("Heartbeat from $deviceId @ $senderIp:${heartbeat.tcpPort}")
    }

    // =========================================================================
    //  Cleanup job
    // =========================================================================

    private fun startCleanupJob() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(Constants.HEARTBEAT_INTERVAL_MS)
                val now     = System.currentTimeMillis()
                val timeout = Constants.PRESENCE_TIMEOUT_MS + Constants.PRESENCE_GRACE_PERIOD_MS

                val timedOut = lastSeenTimestamps.entries
                    .filter { now - it.value > timeout }
                    .map { it.key }

                if (timedOut.isNotEmpty()) {
                    timedOut.forEach {
                        lastSeenTimestamps.remove(it)
                        lastKnownIp.remove(it)
                        Timber.d("Device timed out: $it")
                    }
                    _onlineDevices.update { it - timedOut.toSet() }
                }
            }
        }
    }
}