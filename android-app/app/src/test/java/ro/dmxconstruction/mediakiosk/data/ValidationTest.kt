package ro.dmxconstruction.mediakiosk.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import ro.dmxconstruction.mediakiosk.mediaItem
import ro.dmxconstruction.mediakiosk.snapshot

class ValidationTest {
    @Test fun `contractul JSON real este parsat si ordonat`() {
        val json = """{
          "success":true,
          "device":{"id":7,"name":"Tabletă"},
          "playlist":{"id":9,"name":"Recepție","version":3,"published_at":"2026-01-01T00:00:00Z"},
          "items":[
            {"id":2,"media_id":12,"type":"video","title":"V","url":"https://kiosk.example/media/v.mp4","mime_type":"video/mp4","file_size":20,"checksum":null,"duration_seconds":null,"position":2},
            {"id":1,"media_id":11,"type":"image","title":"I","url":"https://kiosk.example/media/i.png","mime_type":"image/png","file_size":10,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","duration_seconds":8,"position":1}
          ]} """
        val parsed = Gson().fromJson(json, PlaylistResponse::class.java)
        val result = PlaylistValidator.validate(parsed, "https://kiosk.example/", "\"playlist-9-v3\"")
        assertEquals(listOf(1L, 2L), result.items.map { it.id })
        assertEquals(8, result.items.first().durationSeconds)
        assertEquals("\"playlist-9-v3\"", result.etag)
    }

    @Test fun `tipurile URL-urile duratele si pozitiile invalide sunt ignorate`() {
        val valid = mediaItem(id = 8, position = 2)
        val response = PlaylistResponse(
            success = true,
            device = DeviceDto(1, "Tabletă"),
            playlist = PlaylistDto(2, "P", 1),
            items = listOf(
                valid.copy(id = 1, type = "audio", position = 1),
                valid.copy(id = 2, url = "http://kiosk.example/a", position = 3),
                valid.copy(id = 3, url = "https://evil.example/a", position = 4),
                valid.copy(id = 4, durationSeconds = 0, position = 5),
                valid.copy(id = 5, checksum = "invalid", position = 6),
                valid,
                valid.copy(id = 9, position = 2)
            )
        )
        assertEquals(listOf(8L), PlaylistValidator.validate(response, "https://kiosk.example", null).items.map { it.id })
    }

    @Test fun `un raspuns cu materiale dar fara niciun material valid este respins`() {
        val response = PlaylistResponse(true, device = DeviceDto(1, "T"), playlist = PlaylistDto(2, "P", 1),
            items = listOf(mediaItem().copy(type = "unknown")))
        assertThrows(IllegalArgumentException::class.java) {
            PlaylistValidator.validate(response, "https://kiosk.example", null)
        }
    }

    @Test fun `serverul este normalizat si HTTP este refuzat`() {
        assertEquals("https://kiosk.example/base", ServerUrl.normalize(" https://kiosk.example/base/// "))
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.normalize("http://kiosk.example") }
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.normalize("https://kiosk.example?a=1") }
    }

    @Test fun `UUID si backoff sunt validate`() {
        assertTrue(DeviceKeyValidator.isValid(UUID.randomUUID().toString()))
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 300_000L), listOf(0, 1, 2, 20).map(RetryPolicy::delayMillis))
    }

    @Test fun `playlistul nou este schimbat doar la limita dintre materiale`() {
        val switcher = PlaylistSwitcher(snapshot(1))
        switcher.propose(snapshot(2))
        assertEquals(1, switcher.current?.playlist?.version)
        assertEquals(2, switcher.pending?.playlist?.version)
        assertEquals(2, switcher.onItemBoundary()?.playlist?.version)
        assertNull(switcher.pending)
    }

    @Test fun `coada repeta si pastreaza tranzitiile de tip`() {
        val items = listOf(mediaItem(1, "image"), mediaItem(2, "video"), mediaItem(3, "image"))
        val queue = PlaybackQueue()
        assertEquals("image", queue.current(items)?.type)
        assertEquals("video", queue.advance(items)?.type)
        assertEquals("image", queue.advance(items)?.type)
        assertEquals(1L, queue.advance(items)?.id)
    }
}
