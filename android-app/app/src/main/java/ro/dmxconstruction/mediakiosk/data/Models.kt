package ro.dmxconstruction.mediakiosk.data

import com.google.gson.annotations.SerializedName

data class PlaylistResponse(
    val success: Boolean = false,
    val error: String? = null,
    val device: DeviceDto? = null,
    val playlist: PlaylistDto? = null,
    val items: List<MediaItemDto> = emptyList()
)

data class DeviceDto(val id: Long, val name: String)

data class PlaylistDto(
    val id: Long,
    val name: String,
    val version: Int,
    @SerializedName("published_at") val publishedAt: String? = null
)

data class MediaItemDto(
    val id: Long,
    @SerializedName("media_id") val mediaId: Long,
    val type: String,
    val title: String,
    val url: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("file_size") val fileSize: Long,
    val checksum: String? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    val position: Int
)

data class PlaylistSnapshot(
    val device: DeviceDto,
    val playlist: PlaylistDto,
    val items: List<MediaItemDto>,
    val etag: String?,
    val savedAtEpochMs: Long = System.currentTimeMillis()
)

data class HeartbeatResponse(
    val success: Boolean = false,
    @SerializedName("device_id") val deviceId: Long? = null,
    @SerializedName("last_seen_at") val lastSeenAt: String? = null,
    val error: String? = null
)

enum class ScreenOrientation { LANDSCAPE, PORTRAIT, AUTO }

data class AppConfig(
    val serverUrl: String,
    val deviceKey: String,
    val cacheLimitBytes: Long,
    val orientation: ScreenOrientation
)
