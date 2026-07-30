import hashlib
import io
import json
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import importlib
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from PIL import Image
from django.contrib.auth import get_user_model
from django.core.exceptions import ValidationError
from django.core.files.base import ContentFile
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase, override_settings
from django.urls import clear_url_caches, reverse
from django.utils import timezone

from .models import Device, MediaAsset, Playlist, PlaylistItem, PublishedPlaylistItem
from .services import GIBIBYTE
from .video_processing import (
    ProcessingError,
    VideoMetadata,
    build_ffmpeg_command,
    claim_next_job,
    process_claimed_job,
    recover_stale_jobs,
    release_processing_lock,
)


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
        original_file_size=len(content),
        final_file_size=len(content),
        checksum=hashlib.sha256(content).hexdigest(),
        is_active=active,
        processing_status=MediaAsset.READY,
        processing_progress=100,
    )


@override_settings(
    DEBUG=True,
    MEDIA_ROOT=TEST_MEDIA_DIRECTORY.name,
    MEDIA_URL="/media/",
    MAX_IMAGE_UPLOAD_MB=20,
    MAX_VIDEO_UPLOAD_MB=1000,
    MAX_TOTAL_MEDIA_GB=20,
    MIN_FREE_DISK_GB=0,
    FFMPEG_BINARY=sys.executable,
    FFPROBE_BINARY=sys.executable,
    VIDEO_TRANSCODING_ENABLED=True,
    VIDEO_MAX_WIDTH=1920,
    VIDEO_MAX_HEIGHT=1080,
    VIDEO_MAX_FPS=30,
    VIDEO_CRF=23,
    VIDEO_MAX_BITRATE="5M",
    VIDEO_AUDIO_BITRATE="128k",
    VIDEO_PROCESSING_STALE_MINUTES=30,
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
        self.assertEqual(created.processing_status, MediaAsset.QUEUED)
        self.assertEqual(created.processing_progress, 0)
        self.assertFalse(created.file)
        self.assertRegex(created.source_file.name, r"^kiosk/sources/\d{4}/\d{2}/[0-9a-f]{32}\.mp4$")
        self.assertEqual(created.original_file_size, len(content))
        self.assertEqual(created.checksum, "")

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


def queued_video(title="Video în coadă"):
    content = mp4_bytes()
    return MediaAsset.objects.create(
        title=title,
        media_type=MediaAsset.VIDEO,
        source_file=ContentFile(content, name="source.mp4"),
        original_filename="source.mp4",
        mime_type="video/mp4",
        file_size=len(content),
        original_file_size=len(content),
        processing_status=MediaAsset.QUEUED,
        processing_progress=0,
    )


SOURCE_METADATA = VideoMetadata(
    duration=10.0, width=3840, height=2160, fps=60.0,
    video_codec="hevc", audio_codec="opus", pixel_format="yuv420p",
)
FINAL_METADATA = VideoMetadata(
    duration=10.0, width=1920, height=1080, fps=30.0,
    video_codec="h264", audio_codec="aac", pixel_format="yuv420p",
)


class SuccessfulFFmpegProcess:
    def __init__(self, command, **kwargs):
        self.command = command
        Path(command[-1]).write_bytes(mp4_bytes() + b"optimized")
        self.stdout = iter([
            "out_time_us=1000000\n", "progress=continue\n",
            "out_time_us=6000000\n", "progress=continue\n",
            "out_time_us=10000000\n", "progress=end\n",
        ])
        self.terminated = False

    def wait(self, timeout=None):
        return 0

    def terminate(self):
        self.terminated = True


class FailedFFmpegProcess:
    def __init__(self, command, **kwargs):
        self.command = command
        Path(command[-1]).write_bytes(b"partial")
        self.stdout = iter(["out_time_us=1000000\n", "progress=continue\n"])

    def wait(self, timeout=None):
        return 1

    def terminate(self):
        pass


class VideoProcessingTests(TemporaryMediaTestCase):
    def setUp(self):
        super().setUp()
        self.staff = get_user_model().objects.create_user("transcoder", password="test-pass", is_staff=True)
        self.client.force_login(self.staff)

    def test_image_is_ready_immediately(self):
        response = self.client.post(reverse("media_upload"), {
            "title": "Imagine gata",
            "file": upload("imagine.png", image_bytes(), "image/png"),
        })
        created = MediaAsset.objects.get(title="Imagine gata")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(created.processing_status, MediaAsset.READY)
        self.assertEqual(created.processing_progress, 100)

    def test_global_lock_prevents_two_workers_from_claiming(self):
        first = queued_video("Primul")
        queued_video("Al doilea")
        claimed = claim_next_job(worker_token=uuid.UUID("00000000-0000-0000-0000-000000000001"))
        self.assertEqual(claimed[0], first.pk)
        self.assertIsNone(claim_next_job(worker_token=uuid.UUID("00000000-0000-0000-0000-000000000002")))
        release_processing_lock(claimed[1])

    def test_ffmpeg_command_has_android_compatible_constraints(self):
        command = build_ffmpeg_command(Path("/tmp/source.mp4"), Path("/tmp/output.part.mp4"), SOURCE_METADATA)
        self.assertIsInstance(command, list)
        self.assertIn("libx264", command)
        self.assertIn("main", command)
        self.assertIn("yuv420p", command)
        self.assertIn("0:a:0?", command)
        self.assertIn("+faststart", command)
        video_filter = command[command.index("-vf") + 1]
        self.assertIn("force_original_aspect_ratio=decrease", video_filter)
        self.assertIn("force_divisible_by=2", video_filter)
        self.assertIn("fps=30", video_filter)

    @patch("kiosk.video_processing.probe_video", side_effect=[SOURCE_METADATA, FINAL_METADATA])
    def test_queued_processing_ready_progress_checksum_and_cleanup(self, _probe):
        media = queued_video()
        source_path = Path(media.source_file.path)
        asset_id, token = claim_next_job()
        with patch("kiosk.video_processing._update_progress", wraps=__import__(
            "kiosk.video_processing", fromlist=["_update_progress"]
        )._update_progress) as progress_spy:
            with self.captureOnCommitCallbacks(execute=True):
                result = process_claimed_job(asset_id, token, popen_factory=SuccessfulFFmpegProcess)
        self.assertTrue(result)
        media.refresh_from_db()
        self.assertEqual(media.processing_status, MediaAsset.READY)
        self.assertEqual(media.processing_progress, 100)
        self.assertEqual(media.video_codec, "H264")
        self.assertEqual(media.audio_codec, "AAC")
        self.assertEqual(media.checksum, hashlib.sha256(Path(media.file.path).read_bytes()).hexdigest())
        self.assertFalse(source_path.exists())
        self.assertFalse(list(Path(TEST_MEDIA_DIRECTORY.name).rglob("*.part.mp4")))
        self.assertTrue(any(call.args[2] >= 60 for call in progress_spy.call_args_list))

    @patch("kiosk.video_processing.probe_video", return_value=SOURCE_METADATA)
    def test_processing_failure_keeps_source_and_cleans_part(self, _probe):
        media = queued_video()
        source_path = Path(media.source_file.path)
        asset_id, token = claim_next_job()
        self.assertFalse(process_claimed_job(asset_id, token, popen_factory=FailedFFmpegProcess))
        media.refresh_from_db()
        self.assertEqual(media.processing_status, MediaAsset.FAILED)
        self.assertTrue(source_path.exists())
        self.assertFalse(list(Path(TEST_MEDIA_DIRECTORY.name).rglob("*.part.mp4")))

    @patch("kiosk.video_processing.probe_video", side_effect=ProcessingError("Sursă invalidă"))
    def test_invalid_source_is_failed_and_preserved(self, _probe):
        media = queued_video()
        source_path = Path(media.source_file.path)
        asset_id, token = claim_next_job()
        self.assertFalse(process_claimed_job(asset_id, token, popen_factory=SuccessfulFFmpegProcess))
        media.refresh_from_db()
        self.assertEqual(media.processing_status, MediaAsset.FAILED)
        self.assertIn("Sursă invalidă", media.processing_error)
        self.assertTrue(source_path.exists())

    @patch("kiosk.video_processing.probe_video", side_effect=[SOURCE_METADATA, ProcessingError("Output invalid")])
    def test_invalid_output_is_not_published(self, _probe):
        media = queued_video()
        asset_id, token = claim_next_job()
        self.assertFalse(process_claimed_job(asset_id, token, popen_factory=SuccessfulFFmpegProcess))
        media.refresh_from_db()
        self.assertEqual(media.processing_status, MediaAsset.FAILED)
        self.assertFalse(media.file)
        self.assertFalse(list(Path(TEST_MEDIA_DIRECTORY.name).rglob("*.part.mp4")))

    @patch("kiosk.video_processing.probe_video", side_effect=[SOURCE_METADATA, FINAL_METADATA])
    @patch("kiosk.video_processing.validate_processing_capacity", side_effect=ValidationError("Spațiu insuficient"))
    def test_processing_checks_disk_space(self, _capacity, _probe):
        media = queued_video()
        asset_id, token = claim_next_job()
        self.assertFalse(process_claimed_job(asset_id, token, popen_factory=SuccessfulFFmpegProcess))
        media.refresh_from_db()
        self.assertEqual(media.processing_status, MediaAsset.FAILED)
        self.assertIn("Spațiu insuficient", media.processing_error)
        self.assertTrue(Path(media.source_file.path).exists())

    @override_settings(FFMPEG_BINARY="/missing/ffmpeg", FFPROBE_BINARY="/missing/ffprobe")
    def test_missing_ffmpeg_marks_upload_failed_without_500(self):
        response = self.client.post(reverse("media_upload"), {
            "title": "Fără FFmpeg", "file": upload("clip.mp4", mp4_bytes(), "video/mp4")
        })
        media = MediaAsset.objects.get(title="Fără FFmpeg")
        self.assertEqual(response.status_code, 201)
        self.assertEqual(media.processing_status, MediaAsset.FAILED)
        self.assertIn("FFmpeg", media.processing_error)
        self.assertTrue(Path(media.source_file.path).exists())

    def test_retry_endpoint_and_permissions(self):
        media = queued_video()
        media.processing_status = MediaAsset.FAILED
        media.processing_error = "Eroare"
        media.save()
        self.client.logout()
        self.assertEqual(self.client.get(reverse("media_processing_status") + f"?ids={media.pk}").status_code, 302)
        self.assertEqual(self.client.post(reverse("media_retry", args=[media.pk])).status_code, 302)
        self.client.force_login(self.staff)
        response = self.client.post(reverse("media_retry", args=[media.pk]))
        self.assertEqual(response.status_code, 200)
        media.refresh_from_db()
        self.assertEqual(media.processing_status, MediaAsset.QUEUED)
        status = self.client.get(reverse("media_processing_status") + f"?ids={media.pk}").json()
        self.assertEqual(status["items"][0]["status"], "queued")
        self.assertNotIn("source_file", status["items"][0])

    def test_stale_job_is_recovered(self):
        media = queued_video()
        token = uuid.uuid4()
        stale = timezone.now() - timezone.timedelta(minutes=31)
        media.processing_status = MediaAsset.PROCESSING
        media.processing_started_at = stale
        media.worker_token = token
        media.processing_output = "kiosk/videos/stale.part.mp4"
        media.save()
        part = Path(TEST_MEDIA_DIRECTORY.name) / media.processing_output
        part.parent.mkdir(parents=True, exist_ok=True)
        part.write_bytes(b"partial")
        from .models import MediaProcessingLock
        MediaProcessingLock.objects.update_or_create(singleton=1, defaults={"worker_token": token, "acquired_at": stale})
        self.assertEqual(recover_stale_jobs(), 1)
        media.refresh_from_db()
        self.assertEqual(media.processing_status, MediaAsset.QUEUED)
        self.assertFalse(part.exists())

    def test_delete_queued_video_removes_source_and_part(self):
        media = queued_video()
        source_path = Path(media.source_file.path)
        part = Path(TEST_MEDIA_DIRECTORY.name) / "kiosk/videos/delete.part.mp4"
        part.parent.mkdir(parents=True, exist_ok=True)
        part.write_bytes(b"partial")
        media.processing_output = "kiosk/videos/delete.part.mp4"
        media.save(update_fields=["processing_output", "updated_at"])
        with self.captureOnCommitCallbacks(execute=True):
            media.delete()
        self.assertFalse(source_path.exists())
        self.assertFalse(part.exists())

    def test_delete_rejects_processing_path_outside_media_root(self):
        media = queued_video()
        with tempfile.NamedTemporaryFile(delete=False) as outside_file:
            outside_path = Path(outside_file.name)
        try:
            media.processing_output = str(outside_path)
            media.save(update_fields=["processing_output", "updated_at"])
            with self.captureOnCommitCallbacks(execute=True):
                media.delete()
            self.assertTrue(outside_path.exists())
        finally:
            outside_path.unlink(missing_ok=True)

    @patch("kiosk.video_processing.probe_video", side_effect=[SOURCE_METADATA, VideoMetadata(
        duration=10, width=1280, height=720, fps=24, video_codec="h264", audio_codec="", pixel_format="yuv420p"
    )])
    def test_video_without_audio_succeeds(self, _probe):
        media = queued_video()
        asset_id, token = claim_next_job()
        with self.captureOnCommitCallbacks(execute=True):
            self.assertTrue(process_claimed_job(asset_id, token, popen_factory=SuccessfulFFmpegProcess))
        media.refresh_from_db()
        self.assertEqual(media.audio_codec, "")

    def test_unready_video_cannot_be_added_or_published(self):
        media = queued_video()
        playlist = Playlist.objects.create(name="Blocat")
        response = self.client.post(
            reverse("playlist_add_item", args=[playlist.pk]),
            data=json.dumps({"media_asset_id": media.pk}), content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)
        PlaylistItem.objects.create(playlist=playlist, media_asset=media, position=1)
        response = self.client.post(reverse("playlist_publish", args=[playlist.pk]))
        self.assertEqual(response.status_code, 302)
        playlist.refresh_from_db()
        self.assertEqual(playlist.published_version, 0)

    @patch("kiosk.video_processing.probe_video", side_effect=[SOURCE_METADATA, FINAL_METADATA])
    def test_atomic_replacement_updates_etag_and_published_snapshot(self, _probe):
        media = asset("Video publicat", media_type="video")
        old_path = Path(media.file.path)
        playlist = Playlist.objects.create(name="Publicat")
        PlaylistItem.objects.create(playlist=playlist, media_asset=media, position=1)
        playlist.publish()
        old_version = playlist.published_version
        media.source_file.save("reprocess.mp4", ContentFile(mp4_bytes()), save=False)
        media.processing_status = MediaAsset.QUEUED
        media.processing_progress = 0
        media.queued_at = timezone.now()
        media.save()
        asset_id, token = claim_next_job()
        with self.captureOnCommitCallbacks(execute=True):
            self.assertTrue(process_claimed_job(asset_id, token, popen_factory=SuccessfulFFmpegProcess))
        media.refresh_from_db(); playlist.refresh_from_db()
        published = PublishedPlaylistItem.objects.get(media_asset=media)
        self.assertEqual(playlist.published_version, old_version + 1)
        self.assertEqual(published.file_name, media.file.name)
        self.assertEqual(published.checksum, media.checksum)
        self.assertFalse(old_path.exists())


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

    def test_api_excludes_unprocessed_video_source(self):
        self.playlist.publish()
        pending = queued_video("Nepregătit")
        snapshot = self.playlist.published_snapshot
        PublishedPlaylistItem.objects.create(
            published_playlist=snapshot,
            media_asset=pending,
            position=10,
            title=pending.title,
            media_type=MediaAsset.VIDEO,
            file_name=pending.source_file.name,
            mime_type="video/mp4",
            file_size=pending.file_size,
            checksum="source-checksum",
        )
        response = self.client.get(reverse("api_playlist"), **self.headers()).json()
        self.assertNotIn("Nepregătit", [item["title"] for item in response["items"]])

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
