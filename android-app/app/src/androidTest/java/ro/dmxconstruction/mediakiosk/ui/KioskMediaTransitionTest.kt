package ro.dmxconstruction.mediakiosk.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ro.dmxconstruction.mediakiosk.R
import ro.dmxconstruction.mediakiosk.cache.MediaCache
import ro.dmxconstruction.mediakiosk.data.AppConfig
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.DeviceDto
import ro.dmxconstruction.mediakiosk.data.MediaItemDto
import ro.dmxconstruction.mediakiosk.data.PlaylistDto
import ro.dmxconstruction.mediakiosk.data.PlaylistSnapshot
import ro.dmxconstruction.mediakiosk.data.PlaylistStore
import ro.dmxconstruction.mediakiosk.data.ScreenOrientation
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class KioskMediaTransitionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve("playlist.json").delete()
        context.filesDir.resolve("media_cache").deleteRecursively()
        ConfigStore(context).save(
            AppConfig("https://127.0.0.1:1", UUID.randomUUID().toString(), 256L * 1024 * 1024, ScreenOrientation.LANDSCAPE),
            "1234"
        )
    }

    @Test fun imagine_imagine_si_repetare_in_activitate() {
        savePlaylist(listOf("image", "image"))
        ActivityScenario.launch(KioskActivity::class.java).use { scenario ->
            assertTrue(waitForId(scenario, 1, 4_000))
            assertTrue(waitForId(scenario, 2, 4_000))
            assertTrue(waitForId(scenario, 1, 4_000))
        }
    }

    @Test fun imagine_video_activeaza_stratul_ExoPlayer() {
        savePlaylist(listOf("image", "video"))
        ActivityScenario.launch(KioskActivity::class.java).use { scenario ->
            assertTrue(waitForId(scenario, 1, 4_000))
            assertTrue(waitFor(scenario, 5_000) { activity ->
                activity.currentMediaId == 2L && activity.findViewById<View>(R.id.playerView).visibility == View.VISIBLE
            })
        }
    }

    @Test fun video_corupt_este_sarit_catre_imagine() {
        savePlaylist(listOf("video", "image"))
        ActivityScenario.launch(KioskActivity::class.java).use { scenario ->
            assertTrue(waitForId(scenario, 1, 3_000))
            assertTrue(waitFor(scenario, 8_000) { activity ->
                activity.currentMediaId == 2L && activity.findViewById<View>(R.id.imageContainer).visibility == View.VISIBLE
            })
        }
    }

    private fun savePlaylist(types: List<String>) {
        val cache = MediaCache(context.filesDir.resolve("media_cache"), OkHttpClient(), 256L * 1024 * 1024)
        val items = types.mapIndexed { index, type ->
            val id = index.toLong() + 1
            val bytes = if (type == "image") png(if (index == 0) Color.RED else Color.BLUE) else "video-corupt-$id".toByteArray()
            MediaItemDto(
                id, id + 100, type, "Material $id", "https://127.0.0.1:1/media/$id",
                if (type == "image") "image/png" else "video/mp4", bytes.size.toLong(), sha256(bytes),
                if (type == "image") 1 else null, index + 1
            ).also { item ->
                context.filesDir.resolve("media_cache").resolve(requireNotNull(cache.nameFor(item))).writeBytes(bytes)
            }
        }
        PlaylistStore(context.filesDir).save(
            PlaylistSnapshot(DeviceDto(1, "Tabletă test"), PlaylistDto(2, "Test local", 1), items, "\"playlist-2-v1\"")
        )
    }

    private fun waitForId(scenario: ActivityScenario<KioskActivity>, id: Long, timeoutMs: Long): Boolean =
        waitFor(scenario, timeoutMs) { it.currentMediaId == id }

    private fun waitFor(
        scenario: ActivityScenario<KioskActivity>,
        timeoutMs: Long,
        condition: (KioskActivity) -> Boolean
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val matched = AtomicBoolean(false)
            scenario.onActivity { matched.set(condition(it)) }
            if (matched.get()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun png(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
