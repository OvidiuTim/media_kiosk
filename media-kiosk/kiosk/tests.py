import hashlib
import io
import json
import shutil
import struct
import tempfile
import unittest
import importlib
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image
from django.contrib.auth import get_user_model
from django.core.files.base import ContentFile
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase, override_settings
from django.urls import clear_url_caches, reverse

from .models import Device, MediaAsset, Playlist, PlaylistItem
from .services import GIBIBYTE


TEST_MEDIA_DIRECTORY = tempfile.TemporaryDirectory(prefix="media-kiosk-tests-")
unittest.addModuleCleanup(TEST_MEDIA_DIRECTORY.cleanup)


def image_bytes(image_format="PNG"):
    output = io.BytesIO()
    Image.new("RGB", (8, 6), color=(255, 90, 61)).save(output, format=image_format)
    return output.getvalue()


def mp4_bytes():
    payload = b"isom" + struct.pack(">I", 512) + b"isomiso2mp41"
    ftyp = struct.pack(">I4s", 8 + len(payload), b"ftyp") + payload
    mdat = struct.pack(">I4s", 8, b"mdat")
    return ftyp + mdat


def upload(name, content, content_type):
    return SimpleUploadedFile(name, content, content_type=content_type)


def asset(title="Imagine", media_type="image", active=True, suffix=None):
    suffix = suffix or ("png" if media_type == "image" else "mp4")
    content = image_bytes() if media_type == "image" else mp4_bytes()
    mime = "image/png" if media_type == "image" else "video/mp4"
    return MediaAsset.objects.create(
        title=title,
        media_type=media_type,
        file=ContentFile(content, name=f"{title}.{suffix}"),
        original_filename=f"{title}.{suffix}",
        mime_type=mime,
        file_size=len(content),
        checksum=hashlib.sha256(content).hexdigest(),
        is_active=active,
    )


@override_settings(
    DEBUG=True,
    MEDIA_ROOT=TEST_MEDIA_DIRECTORY.name,
    MEDIA_URL="/media/",
    MAX_IMAGE_UPLOAD_MB=20,
    MAX_VIDEO_UPLOAD_MB=1000,
    MAX_TOTAL_MEDIA_GB=20,
    MIN_FREE_DISK_GB=0,
)
class TemporaryMediaTestCase(TestCase):
    def setUp(self):
        super().setUp()
        root = Path(TEST_MEDIA_DIRECTORY.name)
        if root.exists():
            for child in root.iterdir():
                if child.is_dir():
                    shutil.rmtree(child)
                else:
                    child.unlink()


class AuthenticationTests(TemporaryMediaTestCase):
    def setUp(self):
        super().setUp()
        self.staff = get_user_model().objects.create_user("admin", password="test-pass", is_staff=True)
        self.playlist = Playlist.objects.create(name="Test")
        self.device = Device.objects.create(name="Tabletă")
        self.media = asset()

    def test_pages_require_authentication_and_staff_access(self):
        protected = [
            reverse("dashboard"), reverse("media_list"), reverse("media_upload"),
            reverse("playlist_list"), reverse("playlist_edit", args=[self.playlist.pk]),
            reverse("playlist_preview", args=[self.playlist.pk]), reverse("device_list"),
            reverse("device_edit", args=[self.device.pk]),
        ]
        for url in protected:
            with self.subTest(url=url):
                response = self.client.get(url)
                self.assertEqual(response.status_code, 302)
                self.assertIn(reverse("login"), response.url)
        self.client.force_login(self.staff)
        for url in protected:
            with self.subTest(url=url):
                self.assertEqual(self.client.get(url).status_code, 200)


class LocalUploadTests(TemporaryMediaTestCase):
    def setUp(self):
        super().setUp()
        self.staff = get_user_model().objects.create_user("uploader", password="test-pass", is_staff=True)
        self.client.force_login(self.staff)

    def post_upload(self, filename, content, content_type, title="Material test"):
        return self.client.post(reverse("media_upload"), {"title": title, "file": upload(filename, content, content_type)})

    def test_valid_image_upload_checksum_and_uuid_name(self):
        content = image_bytes()
        response = self.post_upload("afis-original.png", content, "image/png", "Afiș")
        self.assertEqual(response.status_code, 201)
        created = MediaAsset.objects.get(title="Afiș")
        self.assertEqual(created.checksum, hashlib.sha256(content).hexdigest())
        self.assertEqual(created.original_filename, "afis-original.png")
        self.assertRegex(created.file.name, r"^kiosk/images/\d{4}/\d{2}/[0-9a-f]{32}\.png$")
        self.assertNotIn("afis-original", created.file.name)
        self.assertTrue(Path(created.file.path).exists())

    def test_valid_mp4_upload(self):
        content = mp4_bytes()
        response = self.post_upload("clip.mp4", content, "video/mp4", "Clip")
        self.assertEqual(response.status_code, 201)
        created = MediaAsset.objects.get(title="Clip")
        self.assertEqual(created.media_type, "video")
        self.assertRegex(created.file.name, r"^kiosk/videos/\d{4}/\d{2}/[0-9a-f]{32}\.mp4$")
        self.assertEqual(created.checksum, hashlib.sha256(content).hexdigest())

    def test_invalid_extension(self):
        response = self.post_upload("fisier.exe", b"invalid", "application/octet-stream")
        self.assertEqual(response.status_code, 400)
        self.assertIn("Format neacceptat", response.json()["error"])

    def test_invalid_mime(self):
        response = self.post_upload("afis.png", image_bytes(), "video/mp4")
        self.assertEqual(response.status_code, 400)
        self.assertIn("MIME", response.json()["error"])

    def test_corrupt_image(self):
        response = self.post_upload("afis.png", b"not-a-real-image", "image/png")
        self.assertEqual(response.status_code, 400)
        self.assertIn("coruptă", response.json()["error"])

    def test_invalid_mp4_without_ftyp(self):
        response = self.post_upload("clip.mp4", b"not-an-mp4-container", "video/mp4")
        self.assertEqual(response.status_code, 400)
        self.assertIn("MP4", response.json()["error"])

    @override_settings(MAX_IMAGE_UPLOAD_MB=0)
    def test_individual_size_limit(self):
        response = self.post_upload("afis.png", image_bytes(), "image/png")
        self.assertEqual(response.status_code, 400)
        self.assertIn("limita de 0 MB", response.json()["error"])

    @patch("kiosk.services.media_directory_size", return_value=GIBIBYTE)
    @override_settings(MAX_TOTAL_MEDIA_GB=1)
    def test_total_storage_limit(self, _size):
        response = self.post_upload("afis.png", image_bytes(), "image/png")
        self.assertEqual(response.status_code, 400)
        self.assertIn("limita totală", response.json()["error"])

    @patch("kiosk.services.shutil.disk_usage", return_value=SimpleNamespace(total=GIBIBYTE, used=0, free=1024))
    @override_settings(MIN_FREE_DISK_GB=1)
    def test_insufficient_disk_space(self, _disk):
        response = self.post_upload("afis.png", image_bytes(), "image/png")
        self.assertEqual(response.status_code, 400)
        self.assertIn("Spațiu insuficient", response.json()["error"])

    @patch("kiosk.views.logger.exception")
    def test_model_save_failure_removes_orphan_file(self, _logger):
        def fail_after_file_write(instance, *args, **kwargs):
            instance.file.save(instance.file.name, instance.file.file, save=False)
            raise RuntimeError("database failure")

        with patch.object(MediaAsset, "save", fail_after_file_write):
            response = self.post_upload("afis.png", image_bytes(), "image/png")

        self.assertEqual(response.status_code, 500)
        self.assertFalse(MediaAsset.objects.exists())
        self.assertEqual([path for path in Path(TEST_MEDIA_DIRECTORY.name).rglob("*") if path.is_file()], [])


class MediaDeletionTests(TemporaryMediaTestCase):
    def setUp(self):
        super().setUp()
        self.staff = get_user_model().objects.create_user("deleter", password="test-pass", is_staff=True)
        self.client.force_login(self.staff)

    def test_permanent_delete_removes_physical_file(self):
        media = asset("De șters")
        file_path = Path(media.file.path)
        self.assertTrue(file_path.exists())
        with self.captureOnCommitCallbacks(execute=True):
            response = self.client.post(reverse("media_delete", args=[media.pk]))
        self.assertEqual(response.status_code, 302)
        self.assertFalse(MediaAsset.objects.filter(pk=media.pk).exists())
        self.assertFalse(file_path.exists())

    def test_used_material_requires_explicit_confirmation(self):
        media = asset("Folosit")
        playlist = Playlist.objects.create(name="Protejat")
        PlaylistItem.objects.create(playlist=playlist, media_asset=media, position=1)
        playlist.publish()
        file_path = Path(media.file.path)
        response = self.client.post(reverse("media_delete", args=[media.pk]))
        self.assertEqual(response.status_code, 200)
        self.assertTrue(MediaAsset.objects.filter(pk=media.pk).exists())
        self.assertTrue(file_path.exists())
        with self.captureOnCommitCallbacks(execute=True):
            response = self.client.post(reverse("media_delete", args=[media.pk]), {"remove_everywhere": "1"})
        self.assertEqual(response.status_code, 302)
        self.assertFalse(MediaAsset.objects.filter(pk=media.pk).exists())
        self.assertFalse(file_path.exists())

    @patch("kiosk.views.logger.exception")
    @patch("kiosk.models.MediaAsset.delete", side_effect=RuntimeError("db failure"))
    def test_database_failure_keeps_physical_file(self, _delete, _logger):
        media = asset("Păstrat")
        file_path = Path(media.file.path)
        response = self.client.post(reverse("media_delete", args=[media.pk]))
        self.assertEqual(response.status_code, 302)
        self.assertTrue(file_path.exists())


class PublicationAndAPITests(TemporaryMediaTestCase):
    def setUp(self):
        super().setUp()
        self.playlist = Playlist.objects.create(name="Recepție")
        self.first = asset("Prima")
        self.second = asset("Video", media_type="video")
        self.inactive = asset("Oprit", active=False)
        PlaylistItem.objects.create(playlist=self.playlist, media_asset=self.second, position=2)
        PlaylistItem.objects.create(playlist=self.playlist, media_asset=self.first, position=1, image_duration_seconds=8)
        PlaylistItem.objects.create(playlist=self.playlist, media_asset=self.inactive, position=3)
        hidden_item = asset("Element inactiv")
        PlaylistItem.objects.create(playlist=self.playlist, media_asset=hidden_item, position=4, is_active=False)
        self.device = Device.objects.create(name="Tabletă Recepție", assigned_playlist=self.playlist)

    def headers(self, device=None):
        return {"HTTP_X_DEVICE_KEY": str((device or self.device).device_key)}

    def test_invalid_inactive_and_unassigned_devices(self):
        self.assertEqual(self.client.get(reverse("api_playlist"), HTTP_X_DEVICE_KEY="invalid").status_code, 401)
        self.device.is_active = False
        self.device.save()
        self.assertEqual(self.client.get(reverse("api_playlist"), **self.headers()).status_code, 403)
        unassigned = Device.objects.create(name="Fără playlist")
        self.assertEqual(self.client.get(reverse("api_playlist"), **self.headers(unassigned)).status_code, 404)

    def test_absolute_urls_order_and_inactive_exclusion(self):
        self.playlist.publish()
        response = self.client.get(reverse("api_playlist"), **self.headers())
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual([row["title"] for row in data["items"]], ["Prima", "Video"])
        self.assertEqual([row["position"] for row in data["items"]], [1, 2])
        self.assertTrue(data["items"][0]["url"].startswith("http://testserver/media/kiosk/images/"))
        self.assertTrue(data["items"][1]["url"].startswith("http://testserver/media/kiosk/videos/"))
        self.assertEqual(data["items"][0]["duration_seconds"], 8)
        self.assertIsNone(data["items"][1]["duration_seconds"])
        self.device.refresh_from_db()
        self.assertIsNotNone(self.device.last_seen_at)

    def test_draft_changes_are_hidden_until_publish(self):
        self.playlist.publish()
        first_item = self.playlist.items.get(media_asset=self.first)
        first_item.image_duration_seconds = 42
        first_item.save()
        self.playlist.name = "Nume doar în draft"
        self.playlist.save()
        draft_only = asset("Doar draft")
        PlaylistItem.objects.create(playlist=self.playlist, media_asset=draft_only, position=5)
        response = self.client.get(reverse("api_playlist"), **self.headers()).json()
        self.assertEqual(response["playlist"]["name"], "Recepție")
        self.assertEqual([row["title"] for row in response["items"]], ["Prima", "Video"])
        self.assertEqual(response["items"][0]["duration_seconds"], 8)
        self.playlist.publish()
        response = self.client.get(reverse("api_playlist"), **self.headers()).json()
        self.assertEqual(response["playlist"]["name"], "Nume doar în draft")
        self.assertIn("Doar draft", [row["title"] for row in response["items"]])
        self.assertEqual(response["items"][0]["duration_seconds"], 42)

    def test_publication_increments_version_and_etag_returns_304(self):
        self.playlist.publish()
        self.playlist.publish()
        self.assertEqual(self.playlist.published_version, 2)
        first = self.client.get(reverse("api_playlist"), **self.headers())
        self.assertEqual(first["ETag"], f'"playlist-{self.playlist.pk}-v2"')
        cached = self.client.get(reverse("api_playlist"), HTTP_IF_NONE_MATCH=first["ETag"], **self.headers())
        self.assertEqual(cached.status_code, 304)
        self.assertEqual(cached["ETag"], first["ETag"])

    def test_heartbeat_updates_last_seen(self):
        response = self.client.post(reverse("api_heartbeat"), **self.headers())
        self.assertEqual(response.status_code, 200)
        self.device.refresh_from_db()
        self.assertIsNotNone(self.device.last_seen_at)


class PreviewAndDevelopmentMediaTests(TemporaryMediaTestCase):
    def setUp(self):
        super().setUp()
        self.staff = get_user_model().objects.create_user("preview", password="test-pass", is_staff=True)
        self.client.force_login(self.staff)
        self.media = asset("Preview")
        self.playlist = Playlist.objects.create(name="Preview local")
        PlaylistItem.objects.create(playlist=self.playlist, media_asset=self.media, position=1, image_duration_seconds=3)
        self.playlist.publish()

    def test_preview_uses_local_media_url(self):
        response = self.client.get(reverse("playlist_preview", args=[self.playlist.pk]))
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, self.media.file.url)
        self.assertContains(response, "fullscreen-button")

    def test_media_is_served_by_django_in_debug(self):
        import media_kiosk.urls

        clear_url_caches()
        importlib.reload(media_kiosk.urls)
        response = self.client.get(self.media.file.url)
        self.assertEqual(response.status_code, 200)
        content = b"".join(response.streaming_content)
        self.assertEqual(content, Path(self.media.file.path).read_bytes())


class PlaylistOrderingEndpointTests(TemporaryMediaTestCase):
    def setUp(self):
        super().setUp()
        self.staff = get_user_model().objects.create_user("editor", password="test-pass", is_staff=True)
        self.client.force_login(self.staff)
        self.playlist = Playlist.objects.create(name="Ordine")
        self.items = [
            PlaylistItem.objects.create(playlist=self.playlist, media_asset=asset(f"Material {index}"), position=index)
            for index in range(1, 4)
        ]

    def test_dedicated_endpoint_safely_reorders_all_items(self):
        ordered_ids = [self.items[2].pk, self.items[0].pk, self.items[1].pk]
        response = self.client.post(
            reverse("playlist_reorder", args=[self.playlist.pk]),
            data=json.dumps({"item_ids": ordered_ids}), content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        stored = list(self.playlist.items.order_by("position").values_list("id", flat=True))
        self.assertEqual(stored, ordered_ids)
        self.assertEqual(list(self.playlist.items.order_by("position").values_list("position", flat=True)), [1, 2, 3])

    def test_reorder_rejects_incomplete_list(self):
        response = self.client.post(
            reverse("playlist_reorder", args=[self.playlist.pk]),
            data=json.dumps({"item_ids": [self.items[0].pk]}), content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)
