package ro.dmxconstruction.mediakiosk.kiosk

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.RuntimeStateStore
import ro.dmxconstruction.mediakiosk.ui.KioskActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val bootState = BootStateStore(context)
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // Configurația, PIN-ul și cache-ul sunt credential-protected. Pornirea este
            // amânată până la USER_UNLOCKED/BOOT_COMPLETED, fără a le copia în Direct Boot storage.
            bootState.markWaitingForUnlock()
            return
        }
        if (action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED)) return
        if (action == Intent.ACTION_USER_UNLOCKED && !bootState.isWaitingForUnlock()) return
        if (!bootState.claimBoot()) return

        val configStore = runCatching { ConfigStore(context) }.getOrNull() ?: return
        val isDeviceOwner = (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isDeviceOwnerApp(context.packageName)
        val decision = BootLaunchPolicy.decide(
            configured = configStore.isConfigured(),
            autostartEnabled = configStore.autostartEnabled,
            sdkInt = Build.VERSION.SDK_INT,
            isDeviceOwner = isDeviceOwner,
            isDefaultHome = HomeLauncherController.isDefaultHome(context)
        )
        val runtime = RuntimeStateStore(context)
        when (decision) {
            BootLaunchDecision.START_ACTIVITY -> {
                val started = runCatching {
                    context.startActivity(
                        Intent(context, KioskActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }.isSuccess
                runtime.lastBootStatus = if (started) {
                    "Media Kiosk a fost pornit după restart."
                } else {
                    "Android a blocat pornirea activității după restart. Configurează Media Kiosk ca aplicație principală."
                }
            }
            BootLaunchDecision.SYSTEM_HOME_LAUNCH ->
                runtime.lastBootStatus = "Media Kiosk este aplicația principală; Android o pornește după restart."
            BootLaunchDecision.BACKGROUND_RESTRICTED ->
                runtime.lastBootStatus = "Pornirea directă este restricționată de Android. Configurează Media Kiosk ca aplicație principală."
            BootLaunchDecision.DISABLED ->
                runtime.lastBootStatus = "Pornirea automată după restart este dezactivată."
            BootLaunchDecision.NOT_CONFIGURED -> Unit
        }
    }
}

internal enum class BootLaunchDecision {
    START_ACTIVITY,
    SYSTEM_HOME_LAUNCH,
    BACKGROUND_RESTRICTED,
    DISABLED,
    NOT_CONFIGURED
}

internal object BootLaunchPolicy {
    fun decide(
        configured: Boolean,
        autostartEnabled: Boolean,
        sdkInt: Int,
        isDeviceOwner: Boolean,
        isDefaultHome: Boolean
    ): BootLaunchDecision = when {
        !configured -> BootLaunchDecision.NOT_CONFIGURED
        !autostartEnabled -> BootLaunchDecision.DISABLED
        isDefaultHome -> BootLaunchDecision.SYSTEM_HOME_LAUNCH
        sdkInt < Build.VERSION_CODES.Q || isDeviceOwner -> BootLaunchDecision.START_ACTIVITY
        else -> BootLaunchDecision.BACKGROUND_RESTRICTED
    }
}

private class BootStateStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context
    private val prefs = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markWaitingForUnlock() {
        prefs.edit().putBoolean(KEY_WAITING, true).apply()
    }

    fun isWaitingForUnlock(): Boolean = prefs.getBoolean(KEY_WAITING, false)

    fun claimBoot(now: Long = System.currentTimeMillis()): Boolean {
        val lastHandled = prefs.getLong(KEY_LAST_HANDLED, 0)
        prefs.edit().putBoolean(KEY_WAITING, false).apply()
        if (now - lastHandled in 0 until DUPLICATE_WINDOW_MS) return false
        prefs.edit().putLong(KEY_LAST_HANDLED, now).apply()
        return true
    }

    companion object {
        const val PREFS = "kiosk_boot_state"
        private const val KEY_WAITING = "waiting_for_unlock"
        private const val KEY_LAST_HANDLED = "last_handled_at"
        private const val DUPLICATE_WINDOW_MS = 60_000L
    }
}
