package ro.dmxconstruction.mediakiosk.ui

import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ro.dmxconstruction.mediakiosk.R
import ro.dmxconstruction.mediakiosk.data.AppConfig
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.ScreenOrientation
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KioskActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun configureLocalOnly() {
        context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve("playlist.json").delete()
        ConfigStore(context).save(
            AppConfig("https://127.0.0.1:1", UUID.randomUUID().toString(), 256L * 1024 * 1024, ScreenOrientation.LANDSCAPE),
            "1234"
        )
    }

    @Suppress("DEPRECATION")
    @Test fun fara_playlist_afiseaza_starea_discreta_si_modul_immersiv() {
        ActivityScenario.launch(KioskActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.unavailableText).isShown)
                val flags = activity.window.decorView.systemUiVisibility
                assertTrue(flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
                assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
            }
        }
    }

    @Test fun cinci_apasari_deschid_dialogul_PIN() {
        ActivityScenario.launch(KioskActivity::class.java).use {
            it.onActivity { activity ->
                repeat(5) { activity.findViewById<View>(R.id.adminHotspot).performClick() }
                assertTrue(activity.isAdminDialogShowing)
            }
        }
    }
}
