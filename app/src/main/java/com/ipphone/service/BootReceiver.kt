package com.ipphone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Restarts the background services after:
 *   • Device reboot (BOOT_COMPLETED)
 *   • App update / reinstall (MY_PACKAGE_REPLACED)
 *
 * Both actions are declared in AndroidManifest.xml.
 *
 * FIX: Previously only BOOT_COMPLETED was handled and only NetworkService was
 * restarted. FileTransferService was not started on boot, so any queued transfer
 * events would be missed. Both services are now started here.
 *
 * The manifest also declares android:exported="true" and includes MY_PACKAGE_REPLACED
 * so that services are restarted after an OTA update without requiring a reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Timber.d("BootReceiver: $action — starting background services")

            // Start the core networking service (discovery, presence, messaging)
            ContextCompat.startForegroundService(
                context,
                Intent(context, NetworkService::class.java)
            )

            // Start the file transfer service so it can observe and queue events
            ContextCompat.startForegroundService(
                context,
                Intent(context, FileTransferService::class.java)
            )
        }
    }
}
