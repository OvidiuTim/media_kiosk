import json
from unittest.mock import Mock, patch

from django.contrib.auth import get_user_model
from django.core import signing
from django.test import TestCase, override_settings
from django.urls import reverse

from .models import Device, MediaAsset, Playlist, PlaylistItem
from .services import validate_upload


def asset(title="Imagine", media_type="image", active=True, suffix="jpg"):
    mime = "image/jpeg" if media_type == "image" else "video/mp4"
    return MediaAsset.objects.create(
        title=title, media_type=media_type, r2_object_key=f"tests/{title}.{suffix}",
        original_filename=f"{title}.{suffix}", mime_type=mime, file_size=1024,
        is_active=active,
    )


class AuthenticationTests(TestCase):
    def setUp(self):
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


class PublicationAndAPITests(TestCase):
    def setUp(self):
        self.playlist = Playlist.objects.create(name="Recepție")
        self.first = asset("Prima")
        self.second = asset("Video", media_type="video", suffix="mp4")
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

    @patch("kiosk.api.R2Service")
    def test_order_inactive_exclusion_and_device_access(self, service_class):
        service_class.return_value.presign_download.side_effect = lambda key: f"https://r2.test/{key}"
        self.playlist.publish()
        response = self.client.get(reverse("api_playlist"), **self.headers())
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual([row["title"] for row in data["items"]], ["Prima", "Video"])
        self.assertEqual([row["position"] for row in data["items"]], [1, 2])
        self.assertEqual(data["items"][0]["duration_seconds"], 8)
        self.assertIsNone(data["items"][1]["duration_seconds"])
        self.device.refresh_from_db()
        self.assertIsNotNone(self.device.last_seen_at)

    @patch("kiosk.api.R2Service")
    def test_draft_changes_are_hidden_until_publish(self, service_class):
        service_class.return_value.presign_download.side_effect = lambda key: f"https://r2.test/{key}"
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

    @patch("kiosk.api.R2Service")
    def test_publication_increments_version_and_etag_returns_304(self, service_class):
        service_class.return_value.presign_download.return_value = "https://r2.test/file"
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


class UploadValidationTests(TestCase):
    def setUp(self):
        self.staff = get_user_model().objects.create_user("admin", password="test-pass", is_staff=True)
        self.client.force_login(self.staff)

    @override_settings(MAX_IMAGE_UPLOAD_MB=1, MAX_VIDEO_UPLOAD_MB=2)
    def test_extension_mime_and_size_are_strictly_validated(self):
        with self.assertRaisesMessage(Exception, "Format neacceptat"):
            validate_upload("fisier.exe", "application/octet-stream", 10)
        with self.assertRaisesMessage(Exception, "MIME"):
            validate_upload("imagine.jpg", "image/png", 10)
        with self.assertRaisesMessage(Exception, "limita"):
            validate_upload("video.mp4", "video/mp4", 3 * 1024 * 1024)
        valid = validate_upload("imagine.JPG", "image/jpeg", 1024, "Campanie")
        self.assertEqual(valid.media_type, "image")

    @patch("kiosk.views.R2Service")
    def test_r2_service_is_mocked_for_presign_and_confirmation(self, service_class):
        service = service_class.return_value
        service.presign_upload.return_value = "https://r2.test/upload"
        service.head.return_value = {"ContentLength": 2048, "ContentType": "image/png"}
        response = self.client.post(
            reverse("upload_presign"),
            data=json.dumps({"filename": "afis.png", "mime_type": "image/png", "file_size": 2048, "title": "Afiș"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertNotIn("R2_SECRET_ACCESS_KEY", json.dumps(data))
        confirmation = self.client.post(
            reverse("upload_confirm"), data=json.dumps({"upload_token": data["upload_token"]}),
            content_type="application/json",
        )
        self.assertEqual(confirmation.status_code, 200)
        created = MediaAsset.objects.get(title="Afiș")
        self.assertEqual(created.file_size, 2048)
        self.assertTrue(created.r2_object_key.startswith("media/"))

    def test_tampered_confirmation_token_is_rejected(self):
        response = self.client.post(
            reverse("upload_confirm"), data=json.dumps({"upload_token": "invalid"}), content_type="application/json"
        )
        self.assertEqual(response.status_code, 400)


class PlaylistOrderingEndpointTests(TestCase):
    def setUp(self):
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
