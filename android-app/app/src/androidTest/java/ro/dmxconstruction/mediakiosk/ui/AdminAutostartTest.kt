package ro.dmxconstruction.mediakiosk.ui

import android.content.Context
import android.view.View
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
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
class AdminAutostartTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun configure() {
        context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigStore(context).save(
            AppConfig(
                "https://127.0.0.1:1", UUID.randomUUID().toString(),
                256L * 1024 * 1024, ScreenOrientation.LANDSCAPE
            ),
            "1234"
        )
    }

    @Test fun autostart_este_implicit_activ_si_poate_fi_dezactivat_din_administrare() {
        ActivityScenario.launch(AdminActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<SwitchCompat>(R.id.autostartSwitch)
                assertTrue(toggle.isShown)
                assertTrue(toggle.isChecked)
                assertTrue(activity.findViewById<View>(R.id.setHomeButton).isShown)
                assertTrue(activity.findViewById<View>(R.id.systemLauncherButton).isShown)
                toggle.isChecked = false
                assertFalse(ConfigStore(activity).autostartEnabled)
            }
        }
    }
}
