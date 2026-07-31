package ro.dmxconstruction.mediakiosk

import android.app.Application
import android.content.Context
import ro.dmxconstruction.mediakiosk.diagnostics.CrashExceptionHandler

class MediaKioskApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        installCrashHandler()
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !is CrashExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(CrashExceptionHandler(this, current))
        }
    }
}
