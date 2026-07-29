from django.core.management.base import BaseCommand

from kiosk.models import Device, MediaAsset, Playlist, PlaylistItem


class Command(BaseCommand):
    help = "Creează date demonstrative idempotente pentru panoul Media Kiosk."

    def handle(self, *args, **options):
        image, _ = MediaAsset.objects.get_or_create(
            r2_object_key="demo/oferta-lunii.jpg",
            defaults={"title": "Oferta lunii", "media_type": "image", "original_filename": "oferta-lunii.jpg", "mime_type": "image/jpeg", "file_size": 245678},
        )
        video, _ = MediaAsset.objects.get_or_create(
            r2_object_key="demo/prezentare.mp4",
            defaults={"title": "Prezentare companie", "media_type": "video", "original_filename": "prezentare.mp4", "mime_type": "video/mp4", "file_size": 20345678},
        )
        playlist, _ = Playlist.objects.get_or_create(name="Playlist Recepție", defaults={"description": "Conținut demonstrativ pentru zona de recepție."})
        if not playlist.items.exists():
            PlaylistItem.objects.create(playlist=playlist, media_asset=image, position=1, image_duration_seconds=8)
            PlaylistItem.objects.create(playlist=playlist, media_asset=video, position=2)
        if playlist.published_version == 0:
            playlist.publish()
        device, _ = Device.objects.get_or_create(name="Tabletă Recepție", defaults={"assigned_playlist": playlist})
        if not device.assigned_playlist:
            device.assigned_playlist = playlist
            device.save(update_fields=["assigned_playlist", "updated_at"])
        self.stdout.write(self.style.SUCCESS(f"Date demo create. Cheie tabletă: {device.device_key}"))
        self.stdout.write("Notă: obiectele demo trebuie încărcate separat în R2 la cheile demo/ pentru a avea preview funcțional.")

