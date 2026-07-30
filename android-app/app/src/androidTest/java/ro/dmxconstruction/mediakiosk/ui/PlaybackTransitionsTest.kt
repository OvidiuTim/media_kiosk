package ro.dmxconstruction.mediakiosk.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ro.dmxconstruction.mediakiosk.data.MediaItemDto
import ro.dmxconstruction.mediakiosk.data.PlaybackQueue

@RunWith(AndroidJUnit4::class)
class PlaybackTransitionsTest {
    @Test fun imagine_imagine_si_repetare_playlist() {
        assertTypes(listOf("image", "image"), listOf("image", "image", "image"))
    }

    @Test fun imagine_video_imagine() {
        assertTypes(listOf("image", "video", "image"), listOf("image", "video", "image", "image"))
    }

    @Test fun video_imagine() {
        assertTypes(listOf("video", "image"), listOf("video", "image", "video"))
    }

    private fun assertTypes(types: List<String>, expected: List<String>) {
        val items = types.mapIndexed { index, type -> item(index.toLong() + 1, type, index + 1) }
        val queue = PlaybackQueue()
        val actual = mutableListOf(requireNotNull(queue.current(items)).type)
        repeat(expected.size - 1) { actual += requireNotNull(queue.advance(items)).type }
        assertEquals(expected, actual)
    }

    private fun item(id: Long, type: String, position: Int) = MediaItemDto(
        id, id, type, "T", "https://127.0.0.1/media/$id",
        if (type == "video") "video/mp4" else "image/png", 1, "a".repeat(64),
        if (type == "image") 1 else null, position
    )
}
