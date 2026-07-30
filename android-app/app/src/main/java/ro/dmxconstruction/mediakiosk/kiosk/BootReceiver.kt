package ro.dmxconstruction.mediakiosk.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.ui.KioskActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED)) return
        if (!ConfigStore(context).isConfigured()) return
        runCatching {
            context.startActivity(Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
