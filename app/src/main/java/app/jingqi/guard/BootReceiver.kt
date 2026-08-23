package app.jingqi.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.jingqi.guard.data.AppState
import app.jingqi.guard.vpn.DnsVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && AppState.wasEnabled()) {
            ContextCompat.startForegroundService(context, Intent(context, DnsVpnService::class.java))
        }
    }
}
