package app.jingqi.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import app.jingqi.guard.data.AppState
import app.jingqi.guard.vpn.DnsVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) &&
            AppState.wasEnabled() && VpnService.prepare(context) == null
        ) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DnsVpnService::class.java))
            }.onFailure {
                // Respect OS background restrictions; opening the app offers recovery.
                Log.w("JingQiBoot", "VPN restart unavailable: ${it.javaClass.simpleName}")
            }
        }
    }
}
