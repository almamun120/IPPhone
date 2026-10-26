package com.ipphone.network.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which side of the Wi-Fi Direct link this phone wants to be.
 * Wi-Fi Direct normally "negotiates" the Group Owner (GO) at random; this lets the user decide.
 */
enum class P2pRole {
    /** Let Android negotiate (default behaviour). */
    AUTO,
    /** Create the group myself — other phones connect to me. */
    GROUP_OWNER,
    /** Never become the owner — join a group created by another phone. */
    CLIENT
}

@Singleton
class WifiDirectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, context.mainLooper, null)
    private val locationManager: LocationManager? = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _role = MutableStateFlow(P2pRole.AUTO)
    val role: StateFlow<P2pRole> = _role.asStateFlow()

    /** Details of the current group (name, password, connected clients) or null. */
    private val _group = MutableStateFlow<WifiP2pGroup?>(null)
    val group: StateFlow<WifiP2pGroup?> = _group.asStateFlow()

    /** Last info/error text for the screen. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _locationEnabled = MutableStateFlow(true)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            Timber.d("WifiDirectManager: Received broadcast: ${intent.action}")
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _isWifiP2pEnabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Timber.d("Wifi P2P State Changed: ${if (_isWifiP2pEnabled.value) "ENABLED" else "DISABLED"}")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Timber.d("Wifi P2P Peers Changed - Requesting peers...")
                    manager?.requestPeers(channel) { peerList ->
                        val deviceList = peerList.deviceList.toList()
                        Timber.d("Found ${deviceList.size} peers:")
                        deviceList.forEach { device ->
                            Timber.d(" - Device: ${device.deviceName} (${device.deviceAddress}) status: ${device.status}")
                        }
                        _peers.value = deviceList
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val wifiP2pInfo: WifiP2pInfo? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    Timber.d("Wifi P2P Connection Changed: Group Formed = ${wifiP2pInfo?.groupFormed}, Owner = ${wifiP2pInfo?.isGroupOwner}")
                    _connectionInfo.value = wifiP2pInfo

                    val groupExtra: WifiP2pGroup? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    }
                    _group.value = if (wifiP2pInfo?.groupFormed == true) groupExtra else null
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    Timber.d("This device changed: ${device?.deviceName} (${device?.deviceAddress})")
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                    _isDiscovering.value = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    Timber.d("Wifi P2P Discovery Changed: ${if (_isDiscovering.value) "STARTED" else "STOPPED"}")
                }
            }
        }
    }

    init {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
        Timber.d("WifiDirectManager initialized and receiver registered")
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        Timber.d("WifiDirectManager: discoverPeers() called")
        
        val isLocationEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        
        _locationEnabled.value = isLocationEnabled
        
        if (!isLocationEnabled) {
            Timber.w("WifiDirectManager: Location services are disabled. Discovery might not work.")
        }

        if (manager == null || channel == null) {
            Timber.e("WifiDirectManager: manager or channel is null")
            return
        }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WifiDirectManager: Discovery Initiation Successful")
            }

            override fun onFailure(reasonCode: Int) {
                val reason = when (reasonCode) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.ERROR -> "ERROR"
                    WifiP2pManager.BUSY -> "BUSY"
                    else -> "UNKNOWN ($reasonCode)"
                }
                Timber.e("WifiDirectManager: Discovery Initiation Failed: $reason")
            }
        })
    }

    // ── Role selection ───────────────────────────────────────────────────────

    fun setRole(role: P2pRole) {
        _role.value = role
        _message.value = when (role) {
            P2pRole.AUTO -> "Automatic: Android decides who becomes the Group Owner."
            P2pRole.GROUP_OWNER -> "Tap “Create group” — this phone will be the Group Owner."
            P2pRole.CLIENT -> "This phone will never become the owner. Tap a Group Owner below."
        }
    }

    /** Become the Group Owner explicitly: other phones connect to this one. */
    @SuppressLint("MissingPermission")
    fun startAsGroupOwner() {
        _role.value = P2pRole.GROUP_OWNER
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            _message.value = "Wi-Fi Direct is not available on this phone."
            return
        }
        _message.value = "Creating group…"

        // createGroup() fails with BUSY when an old group exists, so leave it first.
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = createGroupNow()
            override fun onFailure(reason: Int) = createGroupNow()   // nothing to remove — fine
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroupNow() {
        val m = manager ?: return
        val ch = channel ?: return
        m.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WifiDirectManager: group created (this phone is Group Owner)")
                _message.value = "Group created. This phone is the Group Owner — " +
                    "on the other phone choose “Client” and connect to this device."
                m.requestGroupInfo(ch) { g -> if (g != null) _group.value = g }
            }

            override fun onFailure(reason: Int) {
                Timber.e("WifiDirectManager: createGroup failed: $reason")
                _message.value = "Could not create the group: ${reasonText(reason)}"
            }
        })
    }

    /** Join someone else's group: never become the owner, look for Group Owners. */
    @SuppressLint("MissingPermission")
    fun startAsClient() {
        _role.value = P2pRole.CLIENT
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            _message.value = "Wi-Fi Direct is not available on this phone."
            return
        }
        // If this phone currently owns a group it must give it up first.
        if (_connectionInfo.value?.isGroupOwner == true) {
            m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = discoverPeers()
                override fun onFailure(reason: Int) = discoverPeers()
            })
        } else {
            discoverPeers()
        }
        _message.value = "Searching for Group Owners…"
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // 0 = "please do not make me the owner", 15 = "I want to be the owner", -1 = let Android decide
            groupOwnerIntent = when (_role.value) {
                P2pRole.CLIENT -> 0
                P2pRole.GROUP_OWNER -> 15
                P2pRole.AUTO -> -1
            }
        }

        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Connect success")
                _message.value = "Connecting to ${device.deviceName.ifEmpty { device.deviceAddress }}…"
            }

            override fun onFailure(reason: Int) {
                Timber.e("Connect failed: $reason")
                _message.value = "Connect failed: ${reasonText(reason)}"
            }
        })
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this phone"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy — turn Wi-Fi off and on, then retry"
        WifiP2pManager.ERROR -> "internal error — retry"
        else -> "error $reason"
    }

    fun disconnect() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Disconnected")
                _group.value = null
                _message.value = null
            }

            override fun onFailure(reason: Int) {
                Timber.e("Disconnect failed: $reason")
            }
        })
    }

    private fun Intent.getIntOfExtra(name: String, defaultValue: Int): Int {
        return getIntExtra(name, defaultValue)
    }
}
