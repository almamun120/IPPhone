package com.ipphone.network.presence

import com.ipphone.data.local.database.dao.CallLogDao
import com.ipphone.data.local.database.dao.MessageDao
import com.ipphone.data.local.database.dao.UserDao
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/** Result of checking a number typed on the dial pad. */
sealed class PeerCheck {
    /**
     * The device can be used. [online] = false means it is offline right now but we have
     * chat/call history with it, so only the chat may be opened.
     */
    data class Available(val peerId: String, val online: Boolean) : PeerCheck()

    /** The text is not a valid IPv4 address. */
    object Invalid : PeerCheck()

    /** A valid address, but nobody is there (and we have no history with it). */
    object NotAvailable : PeerCheck()
}

/**
 * Single place that answers "is this peer really reachable?" — used by the dial pad and by
 * the call start, so that audio, video, messages and file transfers are never initialised
 * for a device that is not there.
 */
@Singleton
class PeerAvailability @Inject constructor(
    private val presenceManager: PresenceManager,
    private val connectionManager: ConnectionManager,
    private val userDao: UserDao,
    private val messageDao: MessageDao,
    private val callLogDao: CallLogDao
) {

    fun isValidIpv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
        }
    }

    /** Dial-pad check: validity first, then online status, then a quick live probe, then history. */
    suspend fun checkDialed(input: String): PeerCheck = withContext(Dispatchers.IO) {
        val ip = input.trim()
        if (!isValidIpv4(ip)) return@withContext PeerCheck.Invalid

        val first = ip.substringBefore('.').toInt()
        if (first == 0 || first >= 224) return@withContext PeerCheck.Invalid       // not a host address
        if (first == 127 || isOwnAddress(ip)) return@withContext PeerCheck.NotAvailable

        val user = try { userDao.getUserByIp(ip) } catch (e: Exception) { null }

        if (user != null && isMarkedOnline(user.uuid)) {
            return@withContext PeerCheck.Available(user.uuid, online = true)
        }
        if (probe(ip, user?.tcpPort ?: Constants.TCP_PORT)) {
            return@withContext PeerCheck.Available(user?.uuid ?: ip, online = true)
        }
        if (user != null && hasHistory(user.uuid)) {
            return@withContext PeerCheck.Available(user.uuid, online = false)
        }
        PeerCheck.NotAvailable
    }

    /** True only when the peer is online right now (used before starting any call). */
    suspend fun isOnline(peerId: String): Boolean = withContext(Dispatchers.IO) {
        val byIp = isValidIpv4(peerId)
        val user = try {
            if (byIp) userDao.getUserByIp(peerId) else userDao.getUserById(peerId)
        } catch (e: Exception) {
            null
        }
        if (isMarkedOnline(user?.uuid ?: peerId)) return@withContext true

        val ip = user?.ipAddress?.takeIf { it.isNotBlank() } ?: peerId.takeIf { byIp }
        if (ip == null) false else probe(ip, user?.tcpPort ?: Constants.TCP_PORT)
    }

    private fun isMarkedOnline(uuid: String): Boolean =
        presenceManager.onlineDevices.value.contains(uuid) ||
            connectionManager.connectedDevices.value.contains(uuid)

    private suspend fun hasHistory(uuid: String): Boolean = try {
        messageDao.hasMessages(uuid) || callLogDao.hasCalls(uuid)
    } catch (e: Exception) {
        false
    }

    /** Quick TCP connect to the peer's server port (≈ "is anything listening there?"). */
    private fun probe(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        Timber.d("probe $ip:$port failed: ${e.message}")
        false
    }

    private fun isOwnAddress(ip: String): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
            ni.inetAddresses.toList().any { it is Inet4Address && it.hostAddress == ip }
        }
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 1200
    }
}
