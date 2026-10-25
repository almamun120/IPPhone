package com.ipphone.network.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ipphone.network.discovery.DiscoveryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

sealed class QuickConnectState {
    object Idle : QuickConnectState()
    object HostStarting : QuickConnectState()

    /** Our own private Wi-Fi is up. [qrPayload] is the standard "WIFI:" QR text. */
    data class HostActive(val ssid: String, val password: String, val qrPayload: String) : QuickConnectState()

    data class Joining(val ssid: String) : QuickConnectState()
    data class Joined(val ssid: String, val hostAddress: String?) : QuickConnectState()
    data class Error(val message: String) : QuickConnectState()
}

/**
 * "ShareIt style" connection without touching Settings:
 *  - host:  starts a LocalOnlyHotspot (private Wi-Fi, no internet needed) and exposes a QR code
 *  - guest: scans that QR code and joins through WifiNetworkSpecifier (Android 10+)
 * Once both phones are on the same Wi-Fi, the normal discovery finds the peer.
 */
@Singleton
class QuickConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discoveryManager: DiscoveryManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wifiManager: WifiManager
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow<QuickConnectState>(QuickConnectState.Idle)
    val state: StateFlow<QuickConnectState> = _state.asStateFlow()

    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var joinCallback: ConnectivityManager.NetworkCallback? = null
    private var joinedNetwork: Network? = null

    // ───────────────────────── Host ─────────────────────────

    @SuppressLint("MissingPermission")
    fun startHost() {
        stopHost()
        _state.value = QuickConnectState.HostStarting
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = reservation
                    val info = readHotspot(reservation)
                    if (info == null) {
                        _state.value = QuickConnectState.Error("Could not read the hotspot name/password")
                        return
                    }
                    Timber.d("LocalOnlyHotspot started: ${info.ssid}")
                    _state.value = QuickConnectState.HostActive(
                        ssid = info.ssid,
                        password = info.password,
                        qrPayload = WifiQrCodec.build(info)
                    )
                }

                override fun onStopped() {
                    Timber.d("LocalOnlyHotspot stopped")
                    hotspot = null
                    if (_state.value is QuickConnectState.HostActive) _state.value = QuickConnectState.Idle
                }

                override fun onFailed(reason: Int) {
                    Timber.w("LocalOnlyHotspot failed: $reason")
                    hotspot = null
                    _state.value = QuickConnectState.Error(
                        when (reason) {
                            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No free Wi-Fi channel. Turn Wi-Fi off and on, then retry."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Wi-Fi is busy in another mode (hotspot/Wi-Fi Direct). Turn it off and retry."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Hotspot is not allowed on this phone."
                            else -> "Could not start the hotspot. Make sure Location is ON and Wi-Fi is enabled."
                        }
                    )
                }
            }, mainHandler)
        } catch (e: SecurityException) {
            _state.value = QuickConnectState.Error("Allow Location / Nearby devices permission, then retry.")
        } catch (e: Exception) {
            Timber.e(e, "startLocalOnlyHotspot failed")
            _state.value = QuickConnectState.Error(e.message ?: "Could not start the hotspot")
        }
    }

    fun stopHost() {
        try { hotspot?.close() } catch (_: Exception) {}
        hotspot = null
        if (_state.value is QuickConnectState.HostActive || _state.value is QuickConnectState.HostStarting) {
            _state.value = QuickConnectState.Idle
        }
    }

    @Suppress("DEPRECATION")
    private fun readHotspot(r: WifiManager.LocalOnlyHotspotReservation): WifiQr? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cfg: SoftApConfiguration = r.softApConfiguration
                val ssid = cfg.ssid ?: return null
                val pass = cfg.passphrase ?: ""
                val sae = cfg.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                WifiQr(ssid.trim('"'), pass, if (pass.isEmpty()) "nopass" else if (sae) "SAE" else "WPA")
            } else {
                val cfg: WifiConfiguration = r.wifiConfiguration ?: return null
                val ssid = cfg.SSID?.trim('"') ?: return null
                val pass = cfg.preSharedKey?.trim('"') ?: ""
                WifiQr(ssid, pass, if (pass.isEmpty()) "nopass" else "WPA")
            }
        } catch (e: Exception) {
            Timber.e(e, "readHotspot failed")
            null
        }
    }

    // ───────────────────────── Guest ─────────────────────────

    fun join(qrText: String) {
        val qr = WifiQrCodec.parse(qrText)
        if (qr == null) {
            _state.value = QuickConnectState.Error("This QR code is not a Wi-Fi code.")
            return
        }
        leave()
        _state.value = QuickConnectState.Joining(qr.ssid)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) joinModern(qr) else joinLegacy(qr)
    }

    @SuppressLint("NewApi")
    private fun joinModern(qr: WifiQr) {
        try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(qr.ssid)
            when {
                qr.password.isEmpty() || qr.security.equals("nopass", true) -> Unit
                qr.security.equals("SAE", true) -> builder.setWpa3Passphrase(qr.password)
                else -> builder.setWpa2Passphrase(qr.password)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Timber.d("QuickConnect: joined ${qr.ssid}")
                    joinedNetwork = network
                    // Route this app's sockets through the new (internet-less) Wi-Fi.
                    connectivity.bindProcessToNetwork(network)
                    _state.value = QuickConnectState.Joined(qr.ssid, gatewayOf(connectivity.getLinkProperties(network)))
                    scope.launch {
                        delay(2500)   // let NetworkService restart its stack first
                        discoveryManager.startDiscovery()
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    val current = _state.value
                    if (current is QuickConnectState.Joined) {
                        val gw = gatewayOf(lp)
                        if (gw != null && gw != current.hostAddress) _state.value = current.copy(hostAddress = gw)
                    }
                }

                override fun onUnavailable() {
                    _state.value = QuickConnectState.Error(
                        "Could not join “${qr.ssid}”. Check that the other phone's QR is still showing and you tapped Connect."
                    )
                }

                override fun onLost(network: Network) {
                    Timber.d("QuickConnect: lost ${qr.ssid}")
                    connectivity.bindProcessToNetwork(null)
                    joinedNetwork = null
                    if (_state.value is QuickConnectState.Joined) _state.value = QuickConnectState.Idle
                }
            }
            joinCallback = callback
            connectivity.requestNetwork(request, callback, 30_000)
        } catch (e: Exception) {
            Timber.e(e, "joinModern failed")
            _state.value = QuickConnectState.Error(e.message ?: "Could not join the Wi-Fi")
        }
    }

    @Suppress("DEPRECATION")
    private fun joinLegacy(qr: WifiQr) {
        try {
            val conf = WifiConfiguration().apply {
                SSID = "\"${qr.ssid}\""
                if (qr.password.isEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"${qr.password}\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
            }
            val id = wifiManager.addNetwork(conf)
            if (id == -1) {
                _state.value = QuickConnectState.Error("Android refused the Wi-Fi settings.")
                return
            }
            wifiManager.disconnect()
            wifiManager.enableNetwork(id, true)
            wifiManager.reconnect()
            _state.value = QuickConnectState.Joined(qr.ssid, null)
            scope.launch {
                delay(5000)
                discoveryManager.startDiscovery()
            }
        } catch (e: Exception) {
            Timber.e(e, "joinLegacy failed")
            _state.value = QuickConnectState.Error(e.message ?: "Could not join the Wi-Fi")
        }
    }

    fun leave() {
        joinCallback?.let {
            try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        joinCallback = null
        if (joinedNetwork != null) {
            try { connectivity.bindProcessToNetwork(null) } catch (_: Exception) {}
            joinedNetwork = null
        }
        val s = _state.value
        if (s is QuickConnectState.Joined || s is QuickConnectState.Joining || s is QuickConnectState.Error) {
            _state.value = QuickConnectState.Idle
        }
    }

    fun reset() {
        stopHost()
        leave()
        _state.value = QuickConnectState.Idle
    }

    private fun gatewayOf(lp: LinkProperties?): String? {
        if (lp == null) return null
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (lp.dhcpServerAddress as? Inet4Address)?.hostAddress?.let { return it }
        }
        return null
    }
}
