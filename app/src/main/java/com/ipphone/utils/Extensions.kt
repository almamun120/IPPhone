package com.ipphone.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.Formatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID

fun <T> Flow<T>.catchAndLog(): Flow<T> = catch { e -> Timber.e(e, "Flow error") }

// =========================================================================
//  IP address detection — WiFi client + hotspot AP উভয় mode support করে
// =========================================================================

/**
 * ডিভাইসের সবচেয়ে ভালো IPv4 address বের করে।
 *
 * তিনটি scenario handle করে:
 *
 *  1. WiFi client mode  (সাধারণ ব্যবহার):
 *     WifiManager থেকে IP নেওয়া যায়।
 *
 *  2. Hotspot AP mode (এই ডিভাইসটি hotspot):
 *     WifiManager.connectionInfo.ipAddress = 0 কারণ ডিভাইসটি WiFi STA নয়।
 *     wlan0/ap0/softap0 interface এ real IP থাকে (যেমন 10.51.10.129)।
 *
 *  3. Ethernet / USB tethering:
 *     NetworkInterface enumerate করলে পাওয়া যায়।
 */
fun Context.getDeviceIpAddress(): String? {
    // ── 1. WifiManager (WiFi client mode) ────────────────────────────────
    try {
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip != 0) return Formatter.formatIpAddress(ip)
    } catch (e: Exception) {
        Timber.w(e, "WifiManager IP failed")
    }

    // ── 2. NetworkInterface enumerate (hotspot AP + Ethernet) ────────────
    return getBestLocalIpAddress()
}

/**
 * সব active network interface enumerate করে সবচেয়ে ভালো IPv4 address দেয়।
 *
 * Priority:
 *   1. wlan*, ap*, swlan*, softap* — hotspot AP interface (সর্বোচ্চ priority)
*   2. অন্য যেকোনো private IPv4 (192.168.x.x / 10.x.x.x / 172.16-31.x.x)
*/
fun getBestLocalIpAddress(): String? {
    val hotspotPrefixes = listOf("wlan", "ap", "swlan", "wigig", "softap")
    var fallback: String? = null

    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces()) ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name        = iface.name.lowercase()
            val isHotspot   = hotspotPrefixes.any { name.startsWith(it) }

            for (addr in Collections.list(iface.inetAddresses)) {
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val ip = addr.hostAddress ?: continue
                if (isHotspot) {
                    Timber.v("getBestLocalIpAddress: hotspot iface=${iface.name} ip=$ip")
                    return ip   // hotspot IP পেলে সঙ্গে সঙ্গে return
                }
                if (fallback == null && ip.isPrivateIpv4()) fallback = ip
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "getBestLocalIpAddress: enumerate failed")
    }
    return fallback
}

private fun String.isPrivateIpv4(): Boolean =
    startsWith("192.168.") ||
            startsWith("10.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(this)

fun Context.isWifiConnected(): Boolean {
    return try {
        val cm      = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps    = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (e: Exception) {
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.isWifiEnabled && wm.connectionInfo.networkId != -1
    }
}

// =========================================================================
//  Standard extensions (অপরিবর্তিত)
// =========================================================================

fun String.toUUID(): UUID = UUID.fromString(this)

fun String.md5(): String {
    val digest = MessageDigest.getInstance("MD5").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun ByteArray.md5(): String {
    val digest = MessageDigest.getInstance("MD5").digest(this)
    return digest.toHexString()
}

fun Long.toFormattedTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this))

fun Long.toFormattedDate(): String =
    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(this))

fun Long.toFormattedDateTime(): String =
    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(this))

fun Long.toRelativeTime(): String {
    val diff = System.currentTimeMillis() - this
    return when {
        diff < 60_000       -> "Just now"
        diff < 3_600_000    -> "${diff / 60_000} min ago"
        diff < 86_400_000   -> "${diff / 3_600_000} hours ago"
        diff < 604_800_000  -> "${diff / 86_400_000} days ago"
        else                -> toFormattedDate()
    }
}

fun Long.toFormattedSize(): String {
    val kb = 1024.0; val mb = kb * 1024; val gb = mb * 1024
    return when {
        this < kb -> "$this B"
        this < mb -> "%.1f KB".format(this / kb)
        this < gb -> "%.1f MB".format(this / mb)
        else      -> "%.2f GB".format(this / gb)
    }
}

fun Long.toFormattedDuration(): String {
    val s = this / 1000; val m = s / 60; val h = m / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
    else       "%d:%02d".format(m, s % 60)
}

fun Long.toTransferSpeed(): String {
    val kb = 1024.0; val mb = kb * 1024
    return when {
        this < kb -> "$this B/s"
        this < mb -> "%.1f KB/s".format(this / kb)
        else      -> "%.1f MB/s".format(this / mb)
    }
}

fun InetAddress.isLocalNetwork(): Boolean {
    val a = hostAddress ?: return false
    return a.startsWith("192.168.") || a.startsWith("10.") ||
            (1..15).any { a.startsWith("172.${16 + it}.") || a.startsWith("172.${it}.") }
}