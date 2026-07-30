package ro.dmxconstruction.mediakiosk.kiosk

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ro.dmxconstruction.mediakiosk.data.AppConfig
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.PinResult
import ro.dmxconstruction.mediakiosk.data.ScreenOrientation
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeLauncherControllerTest {
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        application.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
        HomeLauncherController.disableHomeCandidate(application)
    }

    @Test fun `selectarea Home activeaza candidatul si deschide setarile Home`() {
        assertFalse(HomeLauncherController.isHomeCandidateEnabled(application))
        HomeLauncherController.enableHomeCandidate(application)
        assertTrue(HomeLauncherController.isHomeCandidateEnabled(application))
        assertEquals(Settings.ACTION_HOME_SETTINGS, HomeLauncherController.createHomeSelectionIntent(application).action)
    }

    @Test fun `revenirea la launcher este imposibila fara PIN corect`() {
        configurePin()
        HomeLauncherController.enableHomeCandidate(application)
        assertEquals(PinResult.Invalid, HomeLauncherController.disableAfterPin(application, "0000"))
        assertTrue(HomeLauncherController.isHomeCandidateEnabled(application))
        assertEquals(PinResult.Valid, HomeLauncherController.disableAfterPin(application, "9876"))
        assertFalse(HomeLauncherController.isHomeCandidateEnabled(application))
        assertEquals(Settings.ACTION_HOME_SETTINGS, HomeLauncherController.createSystemHomeSettingsIntent().action)
    }

    private fun configurePin() {
        ConfigStore(application).save(
            AppConfig(
                "https://kiosk.example", UUID.randomUUID().toString(),
                ConfigStore.DEFAULT_CACHE, ScreenOrientation.LANDSCAPE
            ),
            "9876"
        )
    }
}
