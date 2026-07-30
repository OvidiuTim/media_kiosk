package ro.dmxconstruction.mediakiosk.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager

object KioskMode {
    fun applyImmersive(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    fun startLockTaskIfAuthorized(activity: Activity): Boolean {
        val manager = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (manager.isDeviceOwnerApp(activity.packageName)) {
            val admin = ComponentName(activity, KioskDeviceAdminReceiver::class.java)
            manager.setLockTaskPackages(admin, arrayOf(activity.packageName))
        }
        if (!manager.isLockTaskPermitted(activity.packageName)) return false
        return runCatching { activity.startLockTask(); true }.getOrDefault(false)
    }

    fun isLockTaskPermitted(context: Context): Boolean {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return manager.isLockTaskPermitted(context.packageName) || manager.isDeviceOwnerApp(context.packageName)
    }

    fun isLockTaskActive(context: Context): Boolean = isLocked(context)

    fun exit(activity: Activity) {
        if (isLocked(activity)) runCatching { activity.stopLockTask() }
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun isLocked(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
        } else {
            @Suppress("DEPRECATION") manager.isInLockTaskMode
        }
    }
}

class KioskDeviceAdminReceiver : DeviceAdminReceiver()
