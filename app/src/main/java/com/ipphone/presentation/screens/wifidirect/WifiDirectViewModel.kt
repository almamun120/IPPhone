package com.ipphone.presentation.screens.wifidirect

import android.net.wifi.p2p.WifiP2pDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.network.discovery.DiscoveryManager
import com.ipphone.network.wifidirect.P2pRole
import com.ipphone.network.wifidirect.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WifiDirectViewModel @Inject constructor(
    private val wifiDirectManager: WifiDirectManager,
    private val discoveryManager: DiscoveryManager
) : ViewModel() {

    val peers: StateFlow<List<WifiP2pDevice>> = wifiDirectManager.peers
    val connectionInfo = wifiDirectManager.connectionInfo
    val isWifiP2pEnabled = wifiDirectManager.isWifiP2pEnabled
    val isDiscovering = wifiDirectManager.isDiscovering
    val locationEnabled = wifiDirectManager.locationEnabled
    val role: StateFlow<P2pRole> = wifiDirectManager.role
    val group = wifiDirectManager.group
    val message = wifiDirectManager.message

    init {
        // Once the Wi-Fi Direct group exists, look for IPPhone peers on its network.
        viewModelScope.launch {
            wifiDirectManager.connectionInfo.collect { info ->
                if (info?.groupFormed == true) {
                    delay(2000)
                    discoveryManager.startDiscovery()
                }
            }
        }
    }

    fun setRole(role: P2pRole) = wifiDirectManager.setRole(role)
    fun startAsGroupOwner() = wifiDirectManager.startAsGroupOwner()
    fun startAsClient() = wifiDirectManager.startAsClient()

    fun discoverPeers() {
        wifiDirectManager.discoverPeers()
    }

    fun connect(device: WifiP2pDevice) {
        wifiDirectManager.connect(device)
    }

    fun disconnect() {
        wifiDirectManager.disconnect()
    }
}
