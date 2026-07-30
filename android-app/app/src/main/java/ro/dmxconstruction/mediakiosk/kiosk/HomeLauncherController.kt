package ro.dmxconstruction.mediakiosk.kiosk

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.PinResult

object HomeLauncherController {
    private fun alias(context: Context) =
        ComponentName(context.packageName, "${context.packageName}.KioskHomeAlias")

    fun enableHomeCandidate(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    fun disableHomeCandidate(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    fun isHomeCandidateEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun isDefaultHome(context: Context): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    fun createHomeSelectionIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }

    fun createSystemHomeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)

    fun disableAfterPin(context: Context, pin: String): PinResult {
        val result = ConfigStore(context).verifyPin(pin)
        if (result == PinResult.Valid) disableHomeCandidate(context)
        return result
    }
}
