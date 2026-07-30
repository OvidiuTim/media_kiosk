package ro.dmxconstruction.mediakiosk.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ConfigStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `PIN-ul este stocat cu salt si hash nu in clar`() {
        val store = ConfigStore(context)
        store.save(config(), "1234")
        val prefs = context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.contains("1234"))
        assertTrue(prefs.getString("pin_salt", null)?.isNotBlank() == true)
        assertTrue(prefs.getString("pin_hash", null)?.isNotBlank() == true)
        assertEquals(PinResult.Valid, store.verifyPin("1234"))
    }

    @Test fun `salvari separate folosesc salturi diferite`() {
        ConfigStore(context).save(config(), "1234")
        val prefs = context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE)
        val first = prefs.getString("pin_hash", null)
        ConfigStore(context).save(config(), "1234")
        assertNotEquals(first, prefs.getString("pin_hash", null))
    }

    @Test fun `trei PIN-uri gresite blocheaza 30 de secunde`() {
        val store = ConfigStore(context).also { it.save(config(), "1234") }
        assertEquals(PinResult.Invalid, store.verifyPin("0", 1000))
        assertEquals(PinResult.Invalid, store.verifyPin("0", 1000))
        assertEquals(PinResult.Locked(30_000), store.verifyPin("0", 1000))
        assertEquals(PinResult.Locked(29_000), store.verifyPin("1234", 2000))
        assertEquals(PinResult.Valid, store.verifyPin("1234", 31_001))
    }

    private fun config() = AppConfig(
        "https://kiosk.example", UUID.randomUUID().toString(),
        ConfigStore.DEFAULT_CACHE, ScreenOrientation.LANDSCAPE
    )
}
