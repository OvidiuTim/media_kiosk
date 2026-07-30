package ro.dmxconstruction.mediakiosk.cache

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.dmxconstruction.mediakiosk.checksum
import ro.dmxconstruction.mediakiosk.mediaItem
import java.io.File

class MediaCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun startServer() { server = MockWebServer().also { it.start() } }
    @After fun stopServer() { server.shutdown() }

    @Test fun `cache miss descarca valideaza iar cache hit nu redescarca`() = runTest {
        val bytes = "continut-valid".toByteArray()
        server.enqueue(MockResponse().setBody(bytes.decodeToString()))
        val item = mediaItem(bytes = bytes, url = server.url("/media").toString())
        val cache = MediaCache(temporary.root, okhttp3.OkHttpClient(), 1024)
        assertNotNull(cache.obtain(item))
        assertNotNull(cache.cached(item))
        assertNotNull(cache.obtain(item))
        assertEquals(1, server.requestCount)
        assertEquals(checksum(bytes), MediaCache.sha256(cache.cached(item)!!))
    }

    @Test fun `checksum gresit elimina fisierul partial`() = runTest {
        val bytes = "continut".toByteArray()
        server.enqueue(MockResponse().setBody(bytes.decodeToString()))
        val item = mediaItem(bytes = bytes, url = server.url("/bad").toString(), checksum = "a".repeat(64))
        val cache = MediaCache(temporary.root, okhttp3.OkHttpClient(), 1024)
        assertNull(cache.obtain(item))
        assertTrue(temporary.root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun `downloadul este reluat cu Range din fisierul partial`() = runTest {
        val bytes = "0123456789".toByteArray()
        val item = mediaItem(bytes = bytes, url = server.url("/range").toString())
        val cache = MediaCache(temporary.root, okhttp3.OkHttpClient(), 1024)
        val finalName = requireNotNull(cache.nameFor(item))
        File(temporary.root, "$finalName.part").writeBytes(bytes.copyOfRange(0, 4))
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 4-9/10")
            .setBody(bytes.copyOfRange(4, 10).decodeToString()))
        assertNotNull(cache.obtain(item))
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test fun `limita cache foloseste LRU si poate evacua un fisier protejat`() = runTest {
        val firstBytes = "11111".toByteArray()
        val secondBytes = "22222".toByteArray()
        server.enqueue(MockResponse().setBody(firstBytes.decodeToString()))
        server.enqueue(MockResponse().setBody(secondBytes.decodeToString()))
        val first = mediaItem(1, bytes = firstBytes, url = server.url("/1").toString())
        val second = mediaItem(2, bytes = secondBytes, url = server.url("/2").toString())
        val cache = MediaCache(temporary.root, okhttp3.OkHttpClient(), 5)
        assertNotNull(cache.obtain(first))
        assertNotNull(cache.obtain(second))
        assertNull(cache.cached(first))
        assertNotNull(cache.cached(second))
        assertTrue(cache.usedBytes() <= 5)
        assertEquals(0, cache.trim(setOf(requireNotNull(cache.nameFor(second)))))
    }

    @Test fun `doar fisierele partiale abandonate sunt curatate`() {
        val recent = File(temporary.root, "recent.part").apply { writeText("x") }
        val old = File(temporary.root, "old.part").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000)
        }
        MediaCache(temporary.root, okhttp3.OkHttpClient(), 10)
        assertTrue(recent.exists())
        assertFalse(old.exists())
    }
}
