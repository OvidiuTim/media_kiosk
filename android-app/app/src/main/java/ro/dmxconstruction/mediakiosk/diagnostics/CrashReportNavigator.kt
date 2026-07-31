package ro.dmxconstruction.mediakiosk.diagnostics

import android.app.Activity
import android.content.Intent

object CrashReportNavigator {
    fun redirectIfNeeded(activity: Activity): Boolean {
        if (activity is CrashReportActivity) return false
        val store = CrashReportStore(activity)
        if (store.consumeDiagnosticFailureBypass()) return false
        if (!store.hasReport()) return false
        activity.startActivity(
            Intent(activity, CrashReportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
        return true
    }
}
