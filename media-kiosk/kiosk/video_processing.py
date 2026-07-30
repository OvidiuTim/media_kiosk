import json
import math
import os
import shutil
import subprocess
import uuid
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path

from django.conf import settings
from django.core.exceptions import ValidationError
from django.core.files import File
from django.core.files.storage import default_storage
from django.db import transaction
from django.db.models import F
from django.utils import timezone

from .models import MediaAsset, MediaProcessingLock, Playlist, PublishedPlaylist, PublishedPlaylistItem
from .services import safe_media_path, sha256_for_path, validate_capacity, validate_processing_capacity


class ProcessingError(Exception):
    pass


class ProcessingStopped(ProcessingError):
    pass


@dataclass(frozen=True)
class VideoMetadata:
    duration: float
    width: int
    height: int
    fps: float
    video_codec: str
    audio_codec: str
    pixel_format: str


def sanitized_error(message):
    text = " ".join(str(message or "").split())
    return (text or "Procesarea video a eșuat.")[:500]


def binary_available(binary):
    candidate = Path(binary).expanduser()
    if candidate.parent != Path("."):
        return candidate.is_file() and os.access(candidate, os.X_OK)
    return shutil.which(binary) is not None


def transcoding_availability():
    if not settings.VIDEO_TRANSCODING_ENABLED:
        return False, "Procesarea video este dezactivată în configurația serverului."
    if not binary_available(settings.FFMPEG_BINARY) or not binary_available(settings.FFPROBE_BINARY):
        return False, "Procesarea video nu este disponibilă: FFmpeg sau ffprobe lipsește de pe server."
    return True, ""


def _fraction(value):
    try:
        parsed = Fraction(str(value))
        return float(parsed) if parsed.denominator else 0.0
    except (ValueError, ZeroDivisionError):
        return 0.0


def probe_video(path, *, runner=subprocess.run):
    command = [
        settings.FFPROBE_BINARY,
        "-v", "error",
        "-show_streams",
        "-show_format",
        "-of", "json",
        str(path),
    ]
    try:
        result = runner(command, capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.SubprocessError) as exc:
        raise ProcessingError("ffprobe nu a putut verifica videoclipul.") from exc
    if result.returncode != 0:
        raise ProcessingError("Sursa video nu poate fi citită sau este coruptă.")
    try:
        data = json.loads(result.stdout)
        streams = data.get("streams") or []
        video = next(
            stream for stream in streams
            if stream.get("codec_type") == "video" and int(stream.get("width") or 0) > 0
        )
    except (json.JSONDecodeError, StopIteration, TypeError, ValueError) as exc:
        raise ProcessingError("Fișierul nu conține un stream video valid.") from exc
    audio = next((stream for stream in streams if stream.get("codec_type") == "audio"), None)
    duration_value = (data.get("format") or {}).get("duration") or video.get("duration")
    try:
        duration = float(duration_value)
    except (TypeError, ValueError):
        duration = 0.0
    if not math.isfinite(duration) or duration <= 0:
        raise ProcessingError("Durata videoclipului nu a putut fi determinată.")
    fps = _fraction(video.get("avg_frame_rate") or video.get("r_frame_rate") or "0")
    return VideoMetadata(
        duration=duration,
        width=int(video.get("width") or 0),
        height=int(video.get("height") or 0),
        fps=fps,
        video_codec=str(video.get("codec_name") or ""),
        audio_codec=str((audio or {}).get("codec_name") or ""),
        pixel_format=str(video.get("pix_fmt") or ""),
    )


def build_ffmpeg_command(source_path, output_path, metadata):
    scale = (
        f"scale=w='min({settings.VIDEO_MAX_WIDTH},iw)':"
        f"h='min({settings.VIDEO_MAX_HEIGHT},ih)':"
        "force_original_aspect_ratio=decrease:force_divisible_by=2"
    )
    filters = [scale]
    if metadata.fps > settings.VIDEO_MAX_FPS:
        filters.append(f"fps={settings.VIDEO_MAX_FPS:g}")
    return [
        settings.FFMPEG_BINARY,
        "-y",
        "-i", str(source_path),
        "-map", "0:v:0",
        "-map", "0:a:0?",
        "-sn",
        "-dn",
        "-map_metadata", "-1",
        "-vf", ",".join(filters),
        "-c:v", "libx264",
        "-profile:v", "main",
        "-level:v", "4.0",
        "-pix_fmt", "yuv420p",
        "-preset", "veryfast",
        "-crf", str(settings.VIDEO_CRF),
        "-maxrate", settings.VIDEO_MAX_BITRATE,
        "-bufsize", "10M",
        "-c:a", "aac",
        "-b:a", settings.VIDEO_AUDIO_BITRATE,
        "-movflags", "+faststart",
        "-progress", "pipe:1",
        "-nostats",
        str(output_path),
    ]


def recover_stale_jobs(now=None):
    now = now or timezone.now()
    cutoff = now - timezone.timedelta(minutes=settings.VIDEO_PROCESSING_STALE_MINUTES)
    stale_outputs = list(
        MediaAsset.objects.filter(
            processing_status=MediaAsset.PROCESSING,
            updated_at__lt=cutoff,
        ).exclude(processing_output="").values_list("processing_output", flat=True)
    )
    with transaction.atomic():
        recovered = MediaAsset.objects.filter(
            processing_status=MediaAsset.PROCESSING,
            updated_at__lt=cutoff,
        ).update(
            processing_status=MediaAsset.QUEUED,
            processing_progress=0,
            processing_error="Procesarea a fost reluată după întreruperea workerului.",
            processing_started_at=None,
            processing_output="",
            worker_token=None,
            worker_pid=None,
            queued_at=now,
        )
        lock, _ = MediaProcessingLock.objects.select_for_update().get_or_create(singleton=1)
        if lock.acquired_at and lock.acquired_at < cutoff:
            lock.worker_token = None
            lock.acquired_at = None
            lock.save(update_fields=["worker_token", "acquired_at"])
    for relative_name in stale_outputs:
        try:
            safe_media_path(relative_name).unlink(missing_ok=True)
        except (OSError, ValueError):
            pass
    return recovered


def claim_next_job(worker_token=None, worker_pid=None):
    token = worker_token or uuid.uuid4()
    with transaction.atomic():
        lock, _ = MediaProcessingLock.objects.select_for_update().get_or_create(singleton=1)
        if lock.worker_token:
            return None
        queryset = MediaAsset.objects.filter(
            media_type=MediaAsset.VIDEO,
            processing_status=MediaAsset.QUEUED,
        ).order_by("queued_at", "pk")
        if transaction.get_connection().features.has_select_for_update_skip_locked:
            queryset = queryset.select_for_update(skip_locked=True)
        else:
            queryset = queryset.select_for_update()
        asset = queryset.first()
        if not asset:
            return None
        now = timezone.now()
        updated = MediaAsset.objects.filter(pk=asset.pk, processing_status=MediaAsset.QUEUED).update(
            processing_status=MediaAsset.PROCESSING,
            processing_progress=0,
            processing_error="",
            processing_started_at=now,
            processing_finished_at=None,
            processing_attempts=F("processing_attempts") + 1,
            worker_token=token,
            worker_pid=worker_pid or os.getpid(),
            updated_at=now,
        )
        if not updated:
            return None
        lock.worker_token = token
        lock.acquired_at = now
        lock.save(update_fields=["worker_token", "acquired_at"])
        return asset.pk, token


def release_processing_lock(token):
    with transaction.atomic():
        lock, _ = MediaProcessingLock.objects.select_for_update().get_or_create(singleton=1)
        if lock.worker_token == token:
            lock.worker_token = None
            lock.acquired_at = None
            lock.save(update_fields=["worker_token", "acquired_at"])


def retry_processing(asset):
    if asset.media_type != MediaAsset.VIDEO or asset.processing_status != MediaAsset.FAILED:
        raise ValidationError("Numai videoclipurile cu procesare eșuată pot fi reîncercate.")
    if not asset.source_file or not default_storage.exists(asset.source_file.name):
        raise ValidationError("Sursa videoclipului nu mai este disponibilă pentru reîncercare.")
    available, error = transcoding_availability()
    if not available:
        raise ValidationError(error)
    asset.processing_status = MediaAsset.QUEUED
    asset.processing_progress = 0
    asset.processing_error = ""
    asset.queued_at = timezone.now()
    asset.processing_started_at = None
    asset.processing_finished_at = None
    asset.worker_token = None
    asset.worker_pid = None
    asset.processing_output = ""
    asset.save(update_fields=[
        "processing_status", "processing_progress", "processing_error", "queued_at",
        "processing_started_at", "processing_finished_at", "worker_token", "worker_pid",
        "processing_output", "updated_at",
    ])


def queue_existing_video(asset):
    if asset.media_type != MediaAsset.VIDEO or asset.processing_status != MediaAsset.READY:
        raise ValidationError("Videoclipul nu poate fi optimizat în starea curentă.")
    if not asset.file or not default_storage.exists(asset.file.name):
        raise ValidationError("Fișierul video existent nu este disponibil pe disc.")
    size = default_storage.size(asset.file.name)
    validate_capacity(size * 2, action="Optimizare")
    now = timezone.now()
    source_name = f"kiosk/sources/{now:%Y/%m}/{uuid.uuid4().hex}.mp4"
    try:
        with default_storage.open(asset.file.name, "rb") as existing:
            saved_name = default_storage.save(source_name, File(existing))
        with transaction.atomic():
            locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
            if locked.processing_status != MediaAsset.READY:
                raise ValidationError("Videoclipul a fost deja introdus în coadă.")
            locked.source_file.name = saved_name
            locked.original_file_size = size
            locked.processing_status = MediaAsset.QUEUED
            locked.processing_progress = 0
            locked.processing_error = ""
            locked.queued_at = now
            locked.processing_started_at = None
            locked.processing_finished_at = None
            locked.save()
    except Exception:
        if "saved_name" in locals():
            default_storage.delete(saved_name)
        raise


def _progress_seconds(values):
    for key in ("out_time_us", "out_time_ms"):
        if key in values:
            try:
                return int(values[key]) / 1_000_000
            except ValueError:
                pass
    value = values.get("out_time")
    if value:
        try:
            hours, minutes, seconds = value.split(":")
            return int(hours) * 3600 + int(minutes) * 60 + float(seconds)
        except (ValueError, TypeError):
            pass
    return 0.0


def _update_progress(asset_id, token, percent):
    now = timezone.now()
    updated = MediaAsset.objects.filter(
        pk=asset_id,
        processing_status=MediaAsset.PROCESSING,
        worker_token=token,
    ).update(processing_progress=max(0, min(99, int(percent))), updated_at=now)
    if updated:
        MediaProcessingLock.objects.filter(worker_token=token).update(acquired_at=now)
    return updated


def _validate_output(metadata):
    if metadata.video_codec != "h264":
        raise ProcessingError("Outputul nu folosește codec video H.264.")
    if metadata.audio_codec and metadata.audio_codec != "aac":
        raise ProcessingError("Outputul nu folosește codec audio AAC.")
    if metadata.pixel_format != "yuv420p":
        raise ProcessingError("Outputul nu folosește formatul de pixeli yuv420p.")
    if metadata.width > settings.VIDEO_MAX_WIDTH or metadata.height > settings.VIDEO_MAX_HEIGHT:
        raise ProcessingError("Outputul depășește rezoluția maximă configurată.")
    if metadata.fps > settings.VIDEO_MAX_FPS + 0.01:
        raise ProcessingError("Outputul depășește numărul maxim de cadre pe secundă.")


def _publish_replacement(asset, final_name, final_size, checksum, metadata, now):
    snapshot_ids = list(
        PublishedPlaylistItem.objects.filter(media_asset=asset).values_list("published_playlist_id", flat=True).distinct()
    )
    PublishedPlaylistItem.objects.filter(media_asset=asset).update(
        file_name=final_name,
        mime_type="video/mp4",
        file_size=final_size,
        checksum=checksum,
        updated_at=now,
    )
    for snapshot_id in snapshot_ids:
        snapshot = PublishedPlaylist.objects.select_for_update().select_related("playlist").get(pk=snapshot_id)
        new_version = snapshot.version + 1
        snapshot.version = new_version
        snapshot.published_at = now
        snapshot.save(update_fields=["version", "published_at", "updated_at"])
        Playlist.objects.filter(pk=snapshot.playlist_id).update(
            published_version=new_version,
            published_at=now,
            updated_at=now,
        )


def process_claimed_job(asset_id, token, *, popen_factory=subprocess.Popen, probe_runner=subprocess.run, should_stop=None):
    part_path = None
    final_path = None
    final_committed = False
    try:
        available, availability_error = transcoding_availability()
        if not available:
            raise ProcessingError(availability_error)
        asset = MediaAsset.objects.get(
            pk=asset_id,
            processing_status=MediaAsset.PROCESSING,
            worker_token=token,
        )
        if not asset.source_file:
            raise ProcessingError("Sursa videoclipului nu este disponibilă.")
        source_path = safe_media_path(asset.source_file.name)
        if not source_path.is_file():
            raise ProcessingError("Sursa videoclipului nu mai există pe disc.")
        source_metadata = probe_video(source_path, runner=probe_runner)
        validate_processing_capacity(source_path.stat().st_size, source_metadata.duration)

        now = timezone.now()
        final_name = f"kiosk/videos/{now:%Y/%m}/{uuid.uuid4().hex}.mp4"
        final_path = safe_media_path(final_name)
        final_path.parent.mkdir(parents=True, exist_ok=True)
        part_name = f"{final_name[:-4]}.part.mp4"
        part_path = safe_media_path(part_name)
        MediaAsset.objects.filter(pk=asset_id, worker_token=token).update(processing_output=part_name)

        command = build_ffmpeg_command(source_path, part_path, source_metadata)
        try:
            process = popen_factory(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                bufsize=1,
            )
        except OSError as exc:
            raise ProcessingError("FFmpeg nu a putut fi pornit.") from exc
        values = {}
        last_percent = -1
        for raw_line in process.stdout or []:
            if should_stop and should_stop():
                process.terminate()
                process.wait(timeout=10)
                raise ProcessingStopped("Procesarea a fost oprită în siguranță.")
            key, separator, value = raw_line.strip().partition("=")
            if not separator:
                continue
            values[key] = value
            seconds = _progress_seconds(values)
            percent = min(99, int(seconds / source_metadata.duration * 100))
            if percent > last_percent:
                if not _update_progress(asset_id, token, percent):
                    process.terminate()
                    process.wait(timeout=10)
                    raise ProcessingStopped("Jobul a fost anulat.")
                last_percent = percent
        return_code = process.wait()
        if return_code != 0:
            raise ProcessingError("FFmpeg nu a putut optimiza videoclipul.")
        if not part_path.is_file() or part_path.stat().st_size <= 0:
            raise ProcessingError("FFmpeg nu a produs un fișier video valid.")

        final_metadata = probe_video(part_path, runner=probe_runner)
        _validate_output(final_metadata)
        checksum = sha256_for_path(part_path)
        final_size = part_path.stat().st_size
        os.replace(part_path, final_path)

        with transaction.atomic():
            locked = MediaAsset.objects.select_for_update().get(
                pk=asset_id,
                processing_status=MediaAsset.PROCESSING,
                worker_token=token,
            )
            old_file_name = locked.file.name
            source_file_name = locked.source_file.name
            locked.file.name = final_name
            locked.source_file.name = ""
            locked.file_size = final_size
            locked.final_file_size = final_size
            locked.checksum = checksum
            locked.mime_type = "video/mp4"
            locked.duration_seconds = final_metadata.duration
            locked.video_width = final_metadata.width
            locked.video_height = final_metadata.height
            locked.video_codec = final_metadata.video_codec.upper()
            locked.audio_codec = final_metadata.audio_codec.upper()
            locked.processing_status = MediaAsset.READY
            locked.processing_progress = 100
            locked.processing_error = ""
            locked.processing_finished_at = timezone.now()
            locked.worker_token = None
            locked.worker_pid = None
            locked.processing_output = ""
            locked.save()
            _publish_replacement(locked, final_name, final_size, checksum, final_metadata, locked.processing_finished_at)

            def clean_old_files():
                for relative_name in {source_file_name, old_file_name}:
                    if relative_name and relative_name != final_name:
                        try:
                            safe_media_path(relative_name).unlink(missing_ok=True)
                        except (OSError, ValueError):
                            pass

            transaction.on_commit(clean_old_files, robust=True)
        final_committed = True
        return True
    except MediaAsset.DoesNotExist:
        return False
    except ProcessingStopped as exc:
        now = timezone.now()
        MediaAsset.objects.filter(pk=asset_id, worker_token=token).update(
            processing_status=MediaAsset.QUEUED,
            processing_progress=0,
            processing_error=sanitized_error(exc),
            queued_at=now,
            processing_started_at=None,
            worker_token=None,
            worker_pid=None,
            processing_output="",
            updated_at=now,
        )
        return False
    except Exception as exc:
        message = sanitized_error(exc)
        MediaAsset.objects.filter(pk=asset_id, worker_token=token).update(
            processing_status=MediaAsset.FAILED,
            processing_error=message,
            processing_finished_at=timezone.now(),
            worker_token=None,
            worker_pid=None,
            processing_output="",
            updated_at=timezone.now(),
        )
        return False
    finally:
        if part_path:
            try:
                part_path.unlink(missing_ok=True)
            except OSError:
                pass
        if final_path and final_path.exists() and not final_committed:
            try:
                final_path.unlink()
            except OSError:
                pass
        release_processing_lock(token)


def process_one_job(*, should_stop=None):
    recover_stale_jobs()
    claimed = claim_next_job()
    if not claimed:
        return False
    asset_id, token = claimed
    process_claimed_job(asset_id, token, should_stop=should_stop)
    return True
