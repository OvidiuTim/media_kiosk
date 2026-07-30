package ro.dmxconstruction.mediakiosk.data

import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ro.dmxconstruction.mediakiosk.mediaItem
import java.util.UUID

class PlaylistRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config = AppConfig(
        "https://kiosk.example",
        UUID.randomUUID().toString(),
        1024,
        ScreenOrientation.LANDSCAPE
    )

    @Test fun `200 valideaza salveaza ETag si transmite cheia exclusiv in header`() = runTest {
        val api = FakeApi().apply {
            playlistResponse = Response.success(validResponse(), Headers.headersOf("ETag", "\"playlist-2-v1\""))
        }
        val store = PlaylistStore(temporary.root)
        val result = PlaylistRepository(store, apiProvider = { api }).sync(config, null)
        assertTrue(result is SyncResult.Updated)
        assertEquals(config.deviceKey, api.lastKey)
        assertEquals(null, api.lastEtag)
        assertEquals("\"playlist-2-v1\"", store.load()?.etag)
    }

    @Test fun `304 pastreaza playlistul offline si foloseste If-None-Match`() = runTest {
        val store = PlaylistStore(temporary.root).also { it.save(ro.dmxconstruction.mediakiosk.snapshot()) }
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"playlist-2-v1\""))
            val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
                .addConverterFactory(GsonConverterFactory.create()).build().create(KioskApi::class.java)
            val result = PlaylistRepository(store, apiProvider = { api }).sync(config)
            assertTrue(result is SyncResult.NotModified)
            assertEquals("\"playlist-2-v1\"", server.takeRequest().getHeader("If-None-Match"))
            assertEquals(1, store.load()?.playlist?.version)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `cheia invalida este distincta si nu sterge fallbackul offline`() = runTest {
        val store = PlaylistStore(temporary.root).also { it.save(ro.dmxconstruction.mediakiosk.snapshot(3)) }
        val api = FakeApi().apply { playlistResponse = Response.error(401, "{}".toResponseBody()) }
        val result = PlaylistRepository(store, apiProvider = { api }).sync(config)
        assertTrue(result is SyncResult.InvalidKey)
        assertEquals(3, store.load()?.playlist?.version)
    }

    @Test fun `doar mesajul 404 Django este acceptat ca dispozitiv fara playlist`() = runTest {
        val api = FakeApi().apply {
            playlistResponse = Response.error(404, "{\"success\":false,\"error\":\"Dispozitivul nu are un playlist asociat.\"}".toResponseBody())
        }
        val repository = PlaylistRepository(PlaylistStore(temporary.root), apiProvider = { api })
        assertTrue(repository.sync(config) is SyncResult.NoPlaylist)
        api.playlistResponse = Response.error(404, "<html>Not found</html>".toResponseBody())
        assertTrue(repository.sync(config) is SyncResult.Failure)
    }

    @Test fun `eroarea de retea pastreaza ultimul playlist`() = runTest {
        val store = PlaylistStore(temporary.root).also { it.save(ro.dmxconstruction.mediakiosk.snapshot(5)) }
        val api = FakeApi().apply { failure = java.net.UnknownHostException("secret-host") }
        val result = PlaylistRepository(store, apiProvider = { api }).sync(config)
        assertTrue(result is SyncResult.Failure)
        assertEquals("Server indisponibil. Se folosește conținutul local.", (result as SyncResult.Failure).message)
        assertEquals(5, PlaylistRepository(store).offline()?.playlist?.version)
    }

    @Test fun `testarea conexiunii poate evita persistenta inainte de salvare`() = runTest {
        val store = PlaylistStore(temporary.root)
        val api = FakeApi().apply { playlistResponse = Response.success(validResponse()) }
        assertTrue(PlaylistRepository(store, apiProvider = { api }).sync(config, persist = false) is SyncResult.Updated)
        assertEquals(null, store.load())
    }

    @Test fun `heartbeatul valideaza succesul`() = runTest {
        val api = FakeApi().apply { heartbeatResponse = Response.success(HeartbeatResponse(true, 1, "acum")) }
        assertTrue(PlaylistRepository(PlaylistStore(temporary.root), apiProvider = { api }).heartbeat(config))
        api.heartbeatResponse = Response.error(403, "{}".toResponseBody())
        assertFalse(PlaylistRepository(PlaylistStore(temporary.root), apiProvider = { api }).heartbeat(config))
    }

    private fun validResponse() = PlaylistResponse(
        true, device = DeviceDto(1, "Tabletă"), playlist = PlaylistDto(2, "Recepție", 1), items = listOf(mediaItem())
    )
}

private class FakeApi : KioskApi {
    var playlistResponse: Response<PlaylistResponse> = Response.error(500, "{}".toResponseBody())
    var heartbeatResponse: Response<HeartbeatResponse> = Response.error(500, "{}".toResponseBody())
    var failure: Exception? = null
    var lastKey: String? = null
    var lastEtag: String? = null

    override suspend fun playlist(deviceKey: String, etag: String?): Response<PlaylistResponse> {
        failure?.let { throw it }
        lastKey = deviceKey
        lastEtag = etag
        return playlistResponse
    }

    override suspend fun heartbeat(deviceKey: String): Response<HeartbeatResponse> {
        failure?.let { throw it }
        lastKey = deviceKey
        return heartbeatResponse
    }
}
