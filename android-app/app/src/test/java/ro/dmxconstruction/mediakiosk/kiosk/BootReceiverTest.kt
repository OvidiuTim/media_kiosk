package ro.dmxconstruction.mediakiosk.kiosk

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ro.dmxconstruction.mediakiosk.data.AppConfig
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.PlaylistStore
import ro.dmxconstruction.mediakiosk.data.ScreenOrientation
import ro.dmxconstruction.mediakiosk.snapshot
import ro.dmxconstruction.mediakiosk.ui.KioskActivity
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootReceiverTest {
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        application.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences("kiosk_runtime_state", Context.MODE_PRIVATE).edit().clear().commit()
        application.createDeviceProtectedStorageContext()
            .getSharedPreferences("kiosk_boot_state", Context.MODE_PRIVATE).edit().clear().commit()
        HomeLauncherController.disableHomeCandidate(application)
        PlaylistStore(application.filesDir).clear()
        while (shadowOf(application).nextStartedActivity != null) Unit
    }

    @Test fun `boot configurat porneste direct KioskActivity pe Android anterior lui 10`() {
        configure()
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(application).nextStartedActivity
        assertEquals(KioskActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `boot neconfigurat nu porneste nicio activitate`() {
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun `autostart activ implicit porneste iar autostart inactiv nu porneste`() {
        configure()
        assertTrue(ConfigStore(application).autostartEnabled)
        ConfigStore(application).autostartEnabled = false
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun `pornirea offline foloseste KioskActivity si pastreaza playlistul local`() {
        configure()
        PlaylistStore(application.filesDir).save(snapshot(7))
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(KioskActivity::class.java.name, shadowOf(application).nextStartedActivity.component?.className)
        assertEquals(7, PlaylistStore(application.filesDir).load()?.playlist?.version)
    }

    @Test fun `locked boot asteapta deblocarea apoi porneste o singura data`() {
        configure()
        val receiver = BootReceiver()
        receiver.onReceive(application, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedActivity)
        receiver.onReceive(application, Intent(Intent.ACTION_USER_UNLOCKED))
        assertEquals(KioskActivity::class.java.name, shadowOf(application).nextStartedActivity.component?.className)
        receiver.onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun `Android modern foloseste Home sau device owner si nu forteaza background launch`() {
        assertEquals(
            BootLaunchDecision.BACKGROUND_RESTRICTED,
            BootLaunchPolicy.decide(true, true, 34, isDeviceOwner = false, isDefaultHome = false)
        )
        assertEquals(
            BootLaunchDecision.SYSTEM_HOME_LAUNCH,
            BootLaunchPolicy.decide(true, true, 34, isDeviceOwner = false, isDefaultHome = true)
        )
        assertEquals(
            BootLaunchDecision.START_ACTIVITY,
            BootLaunchPolicy.decide(true, true, 34, isDeviceOwner = true, isDefaultHome = false)
        )
    }

    private fun configure() {
        ConfigStore(application).save(
            AppConfig(
                "https://127.0.0.1:1", UUID.randomUUID().toString(),
                256L * 1024 * 1024, ScreenOrientation.LANDSCAPE
            ),
            "1234"
        )
    }
}
