package ro.dmxconstruction.mediakiosk

import ro.dmxconstruction.mediakiosk.data.DeviceDto
import ro.dmxconstruction.mediakiosk.data.MediaItemDto
import ro.dmxconstruction.mediakiosk.data.PlaylistDto
import ro.dmxconstruction.mediakiosk.data.PlaylistSnapshot
import java.security.MessageDigest

fun checksum(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

fun mediaItem(
    id: Long = 1,
    type: String = "image",
    position: Int = id.toInt(),
    bytes: ByteArray = "media-$id".toByteArray(),
    url: String = "https://kiosk.example/media/$id",
    checksum: String? = checksum(bytes)
) = MediaItemDto(
    id = id,
    mediaId = id + 100,
    type = type,
    title = "Material $id",
    url = url,
    mimeType = if (type == "video") "video/mp4" else "image/png",
    fileSize = bytes.size.toLong(),
    checksum = checksum,
    durationSeconds = if (type == "image") 5 else null,
    position = position
)

fun snapshot(version: Int = 1, items: List<MediaItemDto> = listOf(mediaItem()), etag: String? = "\"playlist-2-v$version\"") =
    PlaylistSnapshot(DeviceDto(1, "Tabletă test"), PlaylistDto(2, "Recepție", version), items, etag, 1234)
