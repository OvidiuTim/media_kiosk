package ro.dmxconstruction.mediakiosk

import android.app.Application
import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import ro.dmxconstruction.mediakiosk.data.SecureNetwork
import ro.dmxconstruction.mediakiosk.diagnostics.CrashExceptionHandler
import java.io.InputStream

class MediaKioskApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        installCrashHandler()
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        Glide.get(this).registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(SecureNetwork.callFactory(this))
        )
    }

    private fun installCrashHandler() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !is CrashExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(CrashExceptionHandler(this, current))
        }
    }
}
