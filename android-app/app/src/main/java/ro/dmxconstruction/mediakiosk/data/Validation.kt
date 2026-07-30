package ro.dmxconstruction.mediakiosk.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

object ServerUrl {
    fun normalize(raw: String): String {
        val candidate = raw.trim().trimEnd('/')
        val parsed = candidate.toHttpUrlOrNull() ?: throw IllegalArgumentException("Adresa serverului nu este validă.")
        require(parsed.scheme == "https") { "Serverul trebuie să folosească HTTPS." }
        require(parsed.query == null && parsed.fragment == null) { "Adresa serverului nu poate conține query sau fragment." }
        return parsed.newBuilder().encodedPath(parsed.encodedPath.trimEnd('/').ifEmpty { "/" }).build().toString().trimEnd('/')
    }
}

object DeviceKeyValidator {
    fun isValid(value: String): Boolean = runCatching { UUID.fromString(value.trim()) }.isSuccess
}

object PlaylistValidator {
    private val checksumRegex = Regex("^[0-9a-fA-F]{64}$")
    private const val MAX_MEDIA_BYTES = 50L * 1024 * 1024 * 1024

    fun validate(response: PlaylistResponse, serverUrl: String, etag: String?): PlaylistSnapshot {
        require(response.success) { response.error ?: "Răspuns API invalid." }
        val device = requireNotNull(response.device) { "Lipsește dispozitivul din răspuns." }
        val playlist = requireNotNull(response.playlist) { "Lipsește playlistul din răspuns." }
        require(device.id > 0 && device.name.isNotBlank()) { "Dispozitivul din răspuns este invalid." }
        require(playlist.id > 0 && playlist.name.isNotBlank()) { "Playlistul din răspuns este invalid." }
        require(playlist.version >= 1) { "Versiunea playlistului este invalidă." }
        val server = ServerUrl.normalize(serverUrl).toHttpUrlOrNull()!!
        val positions = mutableSetOf<Int>()
        val valid = response.items
            .sortedWith(compareBy<MediaItemDto> { it.position }.thenBy { it.id })
            .mapNotNull { item ->
                runCatching {
                    if (item.id <= 0 || item.mediaId <= 0 || item.type !in setOf("image", "video")) return@runCatching null
                    val url = item.url.toHttpUrlOrNull() ?: return@runCatching null
                    if (url.scheme != "https" || url.host != server.host || url.port != server.port) return@runCatching null
                    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return@runCatching null
                    if (item.position < 1) return@runCatching null
                    if (item.fileSize !in 1..MAX_MEDIA_BYTES || item.title.length > 500) return@runCatching null
                    if (item.type == "image" && ((item.durationSeconds ?: 0) <= 0 || !item.mimeType.startsWith("image/"))) return@runCatching null
                    if (item.type == "video" && !item.mimeType.startsWith("video/")) return@runCatching null
                    if (!item.checksum.isNullOrBlank() && !checksumRegex.matches(item.checksum)) return@runCatching null
                    if (!positions.add(item.position)) return@runCatching null
                    item.copy(checksum = item.checksum?.lowercase())
                }.getOrNull()
            }
        require(response.items.isEmpty() || valid.isNotEmpty()) { "Playlistul nu conține materiale valide." }
        return PlaylistSnapshot(device, playlist, valid, etag)
    }
}

object RetryPolicy {
    fun delayMillis(failureCount: Int): Long {
        val exponent = failureCount.coerceIn(0, 8)
        return (5_000L shl exponent).coerceAtMost(300_000L)
    }
}

class PlaylistSwitcher(initial: PlaylistSnapshot? = null) {
    var current: PlaylistSnapshot? = initial
        private set
    var pending: PlaylistSnapshot? = null
        private set

    fun propose(snapshot: PlaylistSnapshot) {
        if (current == null) current = snapshot
        else if (
            snapshot.device.id != current?.device?.id ||
            snapshot.playlist.id != current?.playlist?.id ||
            snapshot.playlist.version != current?.playlist?.version
        ) pending = snapshot
    }

    fun onItemBoundary(): PlaylistSnapshot? {
        pending?.let { current = it }
        pending = null
        return current
    }
}

class PlaybackQueue {
    private var index = 0
    fun reset() { index = 0 }
    fun current(items: List<MediaItemDto>): MediaItemDto? = items.getOrNull(index.coerceAtMost((items.size - 1).coerceAtLeast(0)))
    fun advance(items: List<MediaItemDto>): MediaItemDto? {
        if (items.isEmpty()) return null
        index = (index + 1) % items.size
        return items[index]
    }
}
