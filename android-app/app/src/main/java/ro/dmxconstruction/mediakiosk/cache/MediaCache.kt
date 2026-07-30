package ro.dmxconstruction.mediakiosk.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.dmxconstruction.mediakiosk.data.MediaItemDto
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class MediaCache(
    private val directory: File,
    private val client: OkHttpClient,
    private val limitBytes: Long
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        directory.mkdirs()
        cleanupPartials()
    }

    fun cached(item: MediaItemDto): File? {
        val file = finalFile(item) ?: return null
        if (!file.isFile || file.length() != item.fileSize) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    suspend fun obtain(item: MediaItemDto, protected: Set<String> = emptySet()): File? = withContext(Dispatchers.IO) {
        cached(item)?.let { return@withContext it }
        val final = finalFile(item) ?: return@withContext null
        val mutex = locks.getOrPut(final.name) { Mutex() }
        mutex.withLock {
            cached(item)?.let { return@withLock it }
            if (item.fileSize > limitBytes || !makeRoom(item.fileSize, protected)) return@withLock null
            download(item, final)
        }
    }

    private suspend fun download(item: MediaItemDto, final: File): File? {
        val part = File(directory, "${final.name}.part")
        if (part.length() >= item.fileSize) part.delete()
        val existing = part.takeIf { it.isFile }?.length() ?: 0L
        val requestBuilder = Request.Builder().url(item.url).header("User-Agent", "MediaKiosk-Android/1.0")
        if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")
        val call = client.newCall(requestBuilder.build())
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
            if (!response.isSuccessful || (response.code != 200 && response.code != 206)) return null
            val append = existing > 0 && response.code == 206
            if (append && !response.header("Content-Range").orEmpty().startsWith("bytes $existing-")) {
                part.delete()
                return null
            }
            if (!append && part.exists()) part.delete()
            val body = response.body ?: return null
            FileOutputStream(part, append).use { output ->
                body.byteStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                output.fd.sync()
            }
            }
        } finally {
            cancellation.dispose()
        }
        if (part.length() != item.fileSize || sha256(part) != item.checksum?.lowercase()) {
            part.delete()
            return null
        }
        if (!part.renameTo(final)) {
            part.delete()
            return null
        }
        final.setLastModified(System.currentTimeMillis())
        return final
    }

    fun cleanupPartials() {
        val abandonedBefore = System.currentTimeMillis() - PARTIAL_MAX_AGE_MS
        directory.listFiles { file -> file.name.endsWith(".part") && file.lastModified() < abandonedBefore }
            ?.forEach { it.delete() }
    }

    fun trim(protected: Set<String> = emptySet()): Int {
        var removed = 0
        val files = mediaFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        val candidates = files.filterNot { it.name in protected } + files.filter { it.name in protected }
        candidates.forEach { file ->
            if (total > limitBytes) {
                val size = file.length()
                if (file.delete()) {
                    total -= size
                    removed++
                }
            }
        }
        return removed
    }

    fun removeUnused(protected: Set<String>): Int {
        var removed = 0
        mediaFiles().filterNot { it.name in protected }.forEach { if (it.delete()) removed++ }
        return removed
    }

    fun usedBytes(): Long = mediaFiles().sumOf { it.length() }
    fun localCount(): Int = mediaFiles().size
    fun nameFor(item: MediaItemDto): String? = finalFile(item)?.name

    private fun makeRoom(required: Long, protected: Set<String>): Boolean {
        val reserve = 100L * 1024 * 1024
        if (directory.usableSpace < required + reserve) return false
        var used = usedBytes()
        if (used + required <= limitBytes) return true
        mediaFiles().filterNot { it.name in protected }.sortedBy { it.lastModified() }.forEach { file ->
            val size = file.length()
            if (file.delete()) used -= size
            if (used + required <= limitBytes) return true
        }
        return used + required <= limitBytes
    }

    private fun finalFile(item: MediaItemDto): File? {
        val checksum = item.checksum?.lowercase()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) } ?: return null
        val extension = if (item.type == "video") "mp4" else "img"
        return File(directory, "${item.mediaId}_${checksum}.$extension")
    }

    private fun mediaFiles(): List<File> = directory.listFiles { file -> file.isFile && !file.name.endsWith(".part") }?.toList().orEmpty()

    companion object {
        private const val PARTIAL_MAX_AGE_MS = 24L * 60 * 60 * 1000

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
