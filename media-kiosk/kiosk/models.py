import uuid
from pathlib import Path

from django.core.validators import MinValueValidator
from django.db import models, transaction
from django.db.models.signals import post_delete
from django.dispatch import receiver
from django.utils import timezone


def media_upload_path(instance, filename):
    extension = Path(filename).suffix.lower()
    folder = "images" if instance.media_type == MediaAsset.IMAGE else "videos"
    now = timezone.now()
    return f"kiosk/{folder}/{now:%Y/%m}/{uuid.uuid4().hex}{extension}"


class TimeStampedModel(models.Model):
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        abstract = True


class Playlist(TimeStampedModel):
    name = models.CharField("nume", max_length=160)
    description = models.TextField("descriere", blank=True)
    is_active = models.BooleanField("activ", default=True)
    published_version = models.PositiveIntegerField("versiune publicată", default=0)
    published_at = models.DateTimeField("publicat la", null=True, blank=True)

    class Meta:
        ordering = ["name"]
        verbose_name = "playlist"
        verbose_name_plural = "playlisturi"

    def __str__(self):
        return self.name

    @transaction.atomic
    def publish(self):
        locked = Playlist.objects.select_for_update().get(pk=self.pk)
        version = locked.published_version + 1
        now = timezone.now()
        snapshot, _ = PublishedPlaylist.objects.get_or_create(playlist=locked)
        snapshot.items.all().delete()
        PublishedPlaylistItem.objects.bulk_create([
            PublishedPlaylistItem(
                published_playlist=snapshot,
                media_asset=item.media_asset,
                position=item.position,
                image_duration_seconds=item.image_duration_seconds,
                is_active=item.is_active,
                title=item.media_asset.title,
                media_type=item.media_asset.media_type,
                file_name=item.media_asset.file.name,
                mime_type=item.media_asset.mime_type,
                file_size=item.media_asset.file_size,
                checksum=item.media_asset.checksum,
            )
            for item in locked.items.select_related("media_asset").order_by("position")
        ])
        snapshot.version = version
        snapshot.name = locked.name
        snapshot.published_at = now
        snapshot.save(update_fields=["version", "name", "published_at", "updated_at"])
        locked.published_version = version
        locked.published_at = now
        locked.save(update_fields=["published_version", "published_at", "updated_at"])
        self.refresh_from_db()
        return snapshot


class Device(TimeStampedModel):
    name = models.CharField("nume", max_length=160)
    device_key = models.UUIDField("cheie dispozitiv", default=uuid.uuid4, unique=True, editable=False)
    is_active = models.BooleanField("activ", default=True)
    assigned_playlist = models.ForeignKey(
        Playlist, verbose_name="playlist asociat", related_name="devices",
        on_delete=models.SET_NULL, null=True, blank=True,
    )
    last_seen_at = models.DateTimeField("văzut ultima dată", null=True, blank=True)

    class Meta:
        ordering = ["name"]
        verbose_name = "dispozitiv"
        verbose_name_plural = "dispozitive"

    def __str__(self):
        return self.name


class MediaAsset(TimeStampedModel):
    IMAGE = "image"
    VIDEO = "video"
    TYPE_CHOICES = [(IMAGE, "Imagine"), (VIDEO, "Videoclip")]

    title = models.CharField("titlu", max_length=200)
    media_type = models.CharField("tip", max_length=10, choices=TYPE_CHOICES)
    file = models.FileField("fișier", upload_to=media_upload_path, max_length=500, blank=True, default="")
    original_filename = models.CharField("nume fișier original", max_length=255)
    mime_type = models.CharField("tip MIME", max_length=100)
    file_size = models.PositiveBigIntegerField("dimensiune")
    checksum = models.CharField("checksum", max_length=128, blank=True, null=True)
    is_active = models.BooleanField("activ", default=True)

    class Meta:
        ordering = ["-created_at"]
        verbose_name = "material media"
        verbose_name_plural = "materiale media"

    def __str__(self):
        return self.title


class PlaylistItem(TimeStampedModel):
    playlist = models.ForeignKey(Playlist, related_name="items", on_delete=models.CASCADE)
    media_asset = models.ForeignKey(MediaAsset, related_name="playlist_items", on_delete=models.PROTECT)
    position = models.PositiveIntegerField(validators=[MinValueValidator(1)])
    image_duration_seconds = models.PositiveIntegerField(default=10, validators=[MinValueValidator(1)])
    is_active = models.BooleanField(default=True)

    class Meta:
        ordering = ["position", "id"]
        constraints = [models.UniqueConstraint(fields=["playlist", "position"], name="unique_draft_position")]

    def __str__(self):
        return f"{self.playlist} · {self.media_asset}"


class PublishedPlaylist(TimeStampedModel):
    playlist = models.OneToOneField(Playlist, related_name="published_snapshot", on_delete=models.CASCADE)
    name = models.CharField(max_length=160, blank=True)
    version = models.PositiveIntegerField(default=0)
    published_at = models.DateTimeField(null=True, blank=True)

    def __str__(self):
        return f"{self.playlist} v{self.version}"


class PublishedPlaylistItem(TimeStampedModel):
    published_playlist = models.ForeignKey(PublishedPlaylist, related_name="items", on_delete=models.CASCADE)
    media_asset = models.ForeignKey(MediaAsset, related_name="published_items", on_delete=models.PROTECT)
    position = models.PositiveIntegerField()
    image_duration_seconds = models.PositiveIntegerField(default=10)
    is_active = models.BooleanField(default=True)
    title = models.CharField(max_length=200)
    media_type = models.CharField(max_length=10, choices=MediaAsset.TYPE_CHOICES)
    file_name = models.CharField(max_length=500, blank=True, default="")
    mime_type = models.CharField(max_length=100)
    file_size = models.PositiveBigIntegerField()
    checksum = models.CharField(max_length=128, blank=True, null=True)

    class Meta:
        ordering = ["position", "id"]
        constraints = [models.UniqueConstraint(fields=["published_playlist", "position"], name="unique_published_position")]


@receiver(post_delete, sender=MediaAsset)
def delete_media_file_after_commit(sender, instance, using, **kwargs):
    if not instance.file or not instance.file.name:
        return
    storage = instance.file.storage
    file_name = instance.file.name

    def remove_file():
        if storage.exists(file_name):
            storage.delete(file_name)

    transaction.on_commit(remove_file, using=using, robust=True)
