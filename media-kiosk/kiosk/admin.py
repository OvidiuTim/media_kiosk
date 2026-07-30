from django.contrib import admin

from .models import Device, MediaAsset, Playlist, PlaylistItem, PublishedPlaylist, PublishedPlaylistItem


class PlaylistItemInline(admin.TabularInline):
    model = PlaylistItem
    extra = 0


@admin.register(Playlist)
class PlaylistAdmin(admin.ModelAdmin):
    list_display = ("name", "is_active", "published_version", "published_at")
    inlines = [PlaylistItemInline]


@admin.register(Device)
class DeviceAdmin(admin.ModelAdmin):
    list_display = ("name", "is_active", "assigned_playlist", "last_seen_at")
    readonly_fields = ("device_key", "last_seen_at")


@admin.register(MediaAsset)
class MediaAssetAdmin(admin.ModelAdmin):
    list_display = ("title", "media_type", "processing_status", "processing_progress", "file_size", "is_active", "created_at")
    list_filter = ("media_type", "processing_status", "is_active")
    search_fields = ("title", "original_filename")
    readonly_fields = (
        "processing_progress", "processing_error", "original_file_size", "final_file_size",
        "duration_seconds", "video_width", "video_height", "video_codec", "audio_codec",
        "queued_at", "processing_started_at", "processing_finished_at", "processing_attempts",
    )


admin.site.register(PublishedPlaylist)
admin.site.register(PublishedPlaylistItem)
