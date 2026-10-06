package com.ipphone.presentation.screens.wifidirect

import android.net.wifi.p2p.WifiP2pDevice
import androidx.lifecycle.ViewModel
import com.ipphone.network.wifidirect.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class WifiDirectViewModel @Inject constructor(
    private val wifiDirectManager: WifiDirectManager
) : ViewModel() {

    val peers: StateFlow<List<WifiP2pDevice>> = wifiDirectManager.peers
    val connectionInfo = wifiDirectManager.connectionInfo
    val isWifiP2pEnabled = wifiDirectManager.isWifiP2pEnabled
    val isDiscovering = wifiDirectManager.isDiscovering
    val locationEnabled = wifiDirectManager.locationEnabled

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
