package com.ipphone.presentation.screens.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.filetransfer.TransferStats
import com.ipphone.network.filetransfer.TransferStatsTracker
import com.ipphone.network.presence.PresenceManager
import com.ipphone.utils.AppDiagnostics
import com.ipphone.utils.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject

data class PeerDiag(
    val uuid: String,
    val name: String,
    val ip: String,
    val port: Int,
    val online: Boolean,
    val connected: Boolean,
    /** Median TCP connect time in ms; -1 = unreachable; null = not measured yet. */
    val rttMs: Long? = null
)

data class DiagnosticsUiState(
    val interfaces: List<String> = emptyList(),
    val tcpPort: Int = 0,
    val dataPort: Int = Constants.DATA_PORT,
    val udpPort: Int = Constants.UDP_PORT,
    val activeConnections: Int = 0,
    val peers: List<PeerDiag> = emptyList(),
    val transfers: List<TransferStats> = emptyList(),
    val uptimeSeconds: Long = 0,
    val processStarts: Int = 0,
    val activityCreates: Int = 0,
    val crashCount: Int = 0,
    val lastCrash: String? = null,
    val exitReasons: List<String> = emptyList(),
    val batteryUnrestricted: Boolean = false,
    val testing: Boolean = false
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
    private val presenceManager: PresenceManager,
    private val statsTracker: TransferStatsTracker,
    private val userDao: UserDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val interfaces = withContext(Dispatchers.IO) { localAddresses() }
            val users = withContext(Dispatchers.IO) {
                try { userDao.getAllKnownUsers() } catch (e: Exception) { emptyList() }
            }
            val online = presenceManager.onlineDevices.value
            val connected = connectionManager.connectedDevices.value
            val previousRtt = _uiState.value.peers.associate { it.uuid to it.rttMs }

            val peers = users.map {
                PeerDiag(
                    uuid = it.uuid,
                    name = it.deviceName,
                    ip = it.ipAddress.orEmpty(),
                    port = it.tcpPort,
                    online = online.contains(it.uuid),
                    connected = connected.contains(it.uuid),
                    rttMs = previousRtt[it.uuid]
                )
            }.sortedWith(compareByDescending<PeerDiag> { it.online }.thenBy { it.name.lowercase() })

            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            _uiState.update {
                it.copy(
                    interfaces = interfaces,
                    tcpPort = connectionManager.getBoundPort(),
                    activeConnections = connectionManager.getActiveConnectionCount(),
                    peers = peers,
                    transfers = statsTracker.stats.value.values.filter { s -> !s.finished },
                    uptimeSeconds = (System.currentTimeMillis() - AppDiagnostics.processStartMs) / 1000,
                    processStarts = AppDiagnostics.processStarts(context),
                    activityCreates = AppDiagnostics.activityCreateCount,
                    crashCount = AppDiagnostics.crashCount(context),
                    lastCrash = AppDiagnostics.lastCrash(context),
                    exitReasons = AppDiagnostics.recentExitReasons(context),
                    batteryUnrestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        pm.isIgnoringBatteryOptimizations(context.packageName)
                )
            }
        }
    }

    /** Measures the TCP connect time (≈ round-trip) to every peer that has an address. */
    fun testPeers() {
        if (_uiState.value.testing) return
        _uiState.update { it.copy(testing = true) }
        viewModelScope.launch {
            val targets = _uiState.value.peers.filter { it.ip.isNotBlank() }
            val results = withContext(Dispatchers.IO) {
                targets.map { peer -> async { peer.uuid to measureRtt(peer.ip, peer.port) } }.awaitAll().toMap()
            }
            _uiState.update { state ->
                state.copy(
                    testing = false,
                    peers = state.peers.map { p -> if (results.containsKey(p.uuid)) p.copy(rttMs = results[p.uuid]) else p }
                )
            }
        }
    }

    fun clearCrash() {
        AppDiagnostics.clearCrash(context)
        refresh()
    }

    /** Plain-text report the user can paste into a bug report. */
    fun buildReport(): String {
        val s = _uiState.value
        return buildString {
            appendLine("IPPhone diagnostics")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Addresses: ${s.interfaces.joinToString()}")
            appendLine("Ports: tcp=${s.tcpPort} udp=${s.udpPort} data=${s.dataPort}; connections=${s.activeConnections}")
            appendLine("Process uptime ${s.uptimeSeconds}s, starts=${s.processStarts}, activity creates=${s.activityCreates}, crashes=${s.crashCount}")
            appendLine("Battery unrestricted: ${s.batteryUnrestricted}")
            appendLine("Peers:")
            s.peers.forEach {
                appendLine("  ${it.name} ${it.ip}:${it.port} online=${it.online} connected=${it.connected} rtt=${it.rttMs}")
            }
            if (s.exitReasons.isNotEmpty()) {
                appendLine("Recent exits:")
                s.exitReasons.forEach { appendLine("  $it") }
            }
            s.lastCrash?.let { appendLine("Last crash:\n$it") }
        }
    }

    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .map { "${ni.name}: ${it.hostAddress}" }
            }
    } catch (e: Exception) {
        emptyList()
    }

    private fun measureRtt(ip: String, port: Int): Long {
        val samples = ArrayList<Long>()
        repeat(3) {
            val start = System.nanoTime()
            try {
                Socket().use { s -> s.connect(InetSocketAddress(ip, port), 1500) }
                samples += (System.nanoTime() - start) / 1_000_000
            } catch (e: Exception) {
                // unreachable sample
            }
        }
        return if (samples.isEmpty()) -1L else samples.sorted()[samples.size / 2]
    }
}
