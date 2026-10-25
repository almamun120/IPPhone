package com.ipphone.presentation.screens.quickconnect

import android.content.Context
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.ipphone.network.wifi.QuickConnectManager
import com.ipphone.network.wifi.QuickConnectState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class QuickConnectViewModel @Inject constructor(
    private val manager: QuickConnectManager
) : ViewModel() {

    val state: StateFlow<QuickConnectState> = manager.state

    fun startHost() = manager.startHost()
    fun stopHost() = manager.stopHost()
    fun leave() = manager.leave()
    fun reset() = manager.reset()

    /** Opens Google's code scanner (no camera permission needed) and joins the scanned Wi-Fi. */
    fun scanAndJoin(context: Context, onScannerUnavailable: () -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val text = barcode.rawValue
                if (text.isNullOrBlank()) onScannerUnavailable() else manager.join(text)
            }
            .addOnCanceledListener { /* user closed the scanner */ }
            .addOnFailureListener { e ->
                Timber.w(e, "Code scanner failed")
                onScannerUnavailable()
            }
    }
}
