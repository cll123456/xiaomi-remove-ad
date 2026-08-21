package dev.hyperadguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.hyperadguard.data.AppState
import dev.hyperadguard.vpn.DnsVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && AppState.wasEnabled()) {
            ContextCompat.startForegroundService(context, Intent(context, DnsVpnService::class.java))
        }
    }
}
