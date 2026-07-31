package ro.dmxconstruction.mediakiosk.ui

import android.content.Context
import android.widget.EditText
import android.widget.Spinner
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class SetupActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("kiosk_runtime_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve("playlist.json").delete()
    }

    @Test fun configurarea_initiala_afiseaza_toate_campurile_si_blocheaza_salvarea() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<EditText>(R.id.serverInput).isShown)
                assertTrue(activity.findViewById<EditText>(R.id.deviceKeyInput).isShown)
                assertTrue(activity.findViewById<Spinner>(R.id.cacheSpinner).isShown)
                assertTrue(activity.findViewById<Spinner>(R.id.orientationSpinner).isShown)
                assertTrue(activity.findViewById<EditText>(R.id.pinInput).isShown)
                val testAction = activity.findViewById<TextView>(R.id.testButton)
                val saveAction = activity.findViewById<TextView>(R.id.saveButton)
                assertTrue(testAction !is Button)
                assertTrue(saveAction !is Button)
                assertFalse(saveAction.isEnabled)
            }
        }
    }

    @Test fun o_configuratie_salvata_navigheaza_catre_player_fara_server_de_productie() {
        ConfigStore(context).save(
            AppConfig("https://127.0.0.1:1", UUID.randomUUID().toString(), 256L * 1024 * 1024, ScreenOrientation.LANDSCAPE),
            "1234"
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(KioskActivity::class.java.name, null, false)
        ActivityScenario.launch(SetupActivity::class.java).use {
            val kiosk = monitor.waitForActivityWithTimeout(5_000)
            assertNotNull(kiosk)
            kiosk?.finish()
        }
        instrumentation.removeMonitor(monitor)
    }
}
