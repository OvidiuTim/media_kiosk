import hashlib
import io

from PIL import Image, ImageDraw
from django.core.files.base import ContentFile
from django.core.management.base import BaseCommand

from kiosk.models import Device, MediaAsset, Playlist, PlaylistItem


def demo_image_bytes():
    output = io.BytesIO()
    image = Image.new("RGB", (1280, 720), color=(20, 33, 61))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((90, 90, 1190, 630), radius=40, fill=(255, 107, 74))
    draw.text((150, 290), "MEDIA KIOSK — CONȚINUT DEMO", fill="white")
    image.save(output, format="PNG", optimize=True)
    return output.getvalue()


class Command(BaseCommand):
    help = "Creează date demonstrative locale, în mod idempotent."

    def handle(self, *args, **options):
        content = demo_image_bytes()
        image, _ = MediaAsset.objects.get_or_create(
            title="Oferta lunii",
            defaults={
                "media_type": "image",
                "original_filename": "oferta-lunii.png",
                "mime_type": "image/png",
                "file_size": len(content),
                "checksum": hashlib.sha256(content).hexdigest(),
            },
        )
        if not image.file or not image.file.storage.exists(image.file.name):
            image.media_type = "image"
            image.original_filename = "oferta-lunii.png"
            image.mime_type = "image/png"
            image.file_size = len(content)
            image.checksum = hashlib.sha256(content).hexdigest()
            image.file.save("oferta-lunii.png", ContentFile(content), save=False)
            image.save()

        playlist, _ = Playlist.objects.get_or_create(
            name="Playlist Recepție",
            defaults={"description": "Conținut demonstrativ pentru zona de recepție."},
        )
        if not playlist.items.filter(media_asset=image).exists():
            next_position = (playlist.items.order_by("-position").values_list("position", flat=True).first() or 0) + 1
            PlaylistItem.objects.create(
                playlist=playlist, media_asset=image, position=next_position, image_duration_seconds=8
            )
        snapshot_has_file = (
            hasattr(playlist, "published_snapshot")
            and playlist.published_snapshot.items.filter(media_asset=image, file_name=image.file.name).exists()
        )
        if not snapshot_has_file:
            playlist.publish()
        device, _ = Device.objects.get_or_create(
            name="Tabletă Recepție", defaults={"assigned_playlist": playlist}
        )
        if not device.assigned_playlist:
            device.assigned_playlist = playlist
            device.save(update_fields=["assigned_playlist", "updated_at"])
        self.stdout.write(self.style.SUCCESS(f"Date demo locale create. Cheie tabletă: {device.device_key}"))
        self.stdout.write(f"Fișier demo: {image.file.path}")
