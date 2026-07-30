package ro.dmxconstruction.mediakiosk.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

class ApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KioskApi
    private val testKey = UUID.randomUUID().toString()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create()).build().create(KioskApi::class.java)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `endpointul playlist foloseste headerele si parseaza JSON-ul real`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"playlist-9-v3\"").setBody(
            """{"success":true,"device":{"id":7,"name":"Tabletă"},"playlist":{"id":9,"name":"P","version":3,"published_at":null},"items":[]}"""
        ))
        val response = api.playlist(testKey, "\"playlist-9-v2\"")
        val request = server.takeRequest()
        assertEquals("/api/kiosk/playlist/", request.path)
        assertEquals(testKey, request.getHeader("X-Device-Key"))
        assertEquals("\"playlist-9-v2\"", request.getHeader("If-None-Match"))
        assertEquals("Tabletă", response.body()?.device?.name)
        assertEquals("\"playlist-9-v3\"", response.headers()["ETag"])
    }

    @Test fun `304 nu incearca sa parseze un corp`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"playlist-1-v1\""))
        val response = api.playlist(testKey, "\"playlist-1-v1\"")
        assertEquals(304, response.code())
        assertNull(response.body())
    }

    @Test fun `heartbeatul foloseste POST pe endpointul real`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"device_id":7,"last_seen_at":"acum"}"""))
        assertEquals(7L, api.heartbeat(testKey).body()?.deviceId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/kiosk/heartbeat/", request.path)
        assertEquals(testKey, request.getHeader("X-Device-Key"))
    }
}
