import hashlib
import os
import shutil
import struct
import warnings
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, UnidentifiedImageError
from django.conf import settings
from django.core.exceptions import ValidationError


MEBIBYTE = 1024 * 1024
GIBIBYTE = 1024 * 1024 * 1024
ALLOWED_UPLOADS = {
    ".jpg": ("image", "image/jpeg", "JPEG"),
    ".jpeg": ("image", "image/jpeg", "JPEG"),
    ".png": ("image", "image/png", "PNG"),
    ".webp": ("image", "image/webp", "WEBP"),
    ".mp4": ("video", "video/mp4", None),
}


@dataclass(frozen=True)
class UploadInspection:
    original_filename: str
    extension: str
    media_type: str
    mime_type: str
    file_size: int
    checksum: str


def ensure_media_root():
    root = Path(settings.MEDIA_ROOT)
    root.mkdir(parents=True, exist_ok=True)
    return root


def media_directory_size(root=None):
    total = 0
    root = Path(root or settings.MEDIA_ROOT)
    if not root.exists():
        return 0
    for directory, _, filenames in os.walk(root, followlinks=False):
        for filename in filenames:
            try:
                total += (Path(directory) / filename).stat().st_size
            except OSError:
                continue
    return total


def format_bytes(value):
    value = max(0, int(value))
    if value >= GIBIBYTE:
        return f"{value / GIBIBYTE:.2f} GB"
    if value >= MEBIBYTE:
        return f"{value / MEBIBYTE:.1f} MB"
    if value >= 1024:
        return f"{value / 1024:.1f} KB"
    return f"{value} B"


def storage_stats():
    root = ensure_media_root()
    used = media_directory_size(root)
    disk = shutil.disk_usage(root)
    limit = int(settings.MAX_TOTAL_MEDIA_GB * GIBIBYTE)
    percent = (used / limit * 100) if limit > 0 else 0
    return {
        "used_bytes": used,
        "used_display": format_bytes(used),
        "free_bytes": disk.free,
        "free_display": format_bytes(disk.free),
        "limit_bytes": limit,
        "limit_display": format_bytes(limit),
        "used_percent": min(100, round(percent, 1)),
    }


def validate_capacity(file_size):
    root = ensure_media_root()
    used = media_directory_size(root)
    total_limit = int(settings.MAX_TOTAL_MEDIA_GB * GIBIBYTE)
    if total_limit > 0 and used + file_size > total_limit:
        raise ValidationError(
            f"Upload refuzat: limita totală de {settings.MAX_TOTAL_MEDIA_GB:g} GB ar fi depășită."
        )
    free = shutil.disk_usage(root).free
    reserve = int(settings.MIN_FREE_DISK_GB * GIBIBYTE)
    if free - file_size < reserve:
        raise ValidationError(
            f"Spațiu insuficient pe disc. Trebuie păstrată o rezervă de cel puțin {settings.MIN_FREE_DISK_GB:g} GB."
        )


def _validate_image(uploaded_file, expected_format):
    uploaded_file.seek(0)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(uploaded_file) as image:
                actual_format = image.format
                image.verify()
    except (UnidentifiedImageError, OSError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise ValidationError("Imaginea este coruptă sau conținutul nu este un format acceptat.")
    finally:
        uploaded_file.seek(0)
    if actual_format != expected_format:
        raise ValidationError("Conținutul imaginii nu corespunde extensiei fișierului.")


def _validate_mp4(uploaded_file, file_size):
    uploaded_file.seek(0)
    position = 0
    scan_limit = min(file_size, MEBIBYTE)
    found_ftyp = False
    try:
        while position + 8 <= scan_limit:
            uploaded_file.seek(position)
            header = uploaded_file.read(8)
            if len(header) != 8:
                break
            box_size, box_type = struct.unpack(">I4s", header)
            header_size = 8
            if box_size == 1:
                extended = uploaded_file.read(8)
                if len(extended) != 8:
                    raise ValidationError("Fișierul MP4 are o structură invalidă.")
                box_size = struct.unpack(">Q", extended)[0]
                header_size = 16
            elif box_size == 0:
                box_size = file_size - position
            if box_size < header_size or position + box_size > file_size:
                raise ValidationError("Fișierul MP4 are o structură invalidă.")
            if box_type == b"ftyp":
                if box_size < header_size + 8:
                    raise ValidationError("Containerul MP4 are un bloc ftyp invalid.")
                major_brand = uploaded_file.read(4)
                if len(major_brand) != 4 or not all(32 <= value <= 126 for value in major_brand):
                    raise ValidationError("Containerul MP4 are o semnătură ftyp invalidă.")
                found_ftyp = True
                break
            position += box_size
    finally:
        uploaded_file.seek(0)
    if not found_ftyp:
        raise ValidationError("Fișierul nu conține semnătura ftyp a unui container MP4 valid.")


def sha256_for_upload(uploaded_file):
    digest = hashlib.sha256()
    uploaded_file.seek(0)
    for chunk in uploaded_file.chunks():
        digest.update(chunk)
    uploaded_file.seek(0)
    return digest.hexdigest()


def inspect_uploaded_file(uploaded_file):
    safe_name = os.path.basename(str(getattr(uploaded_file, "name", "") or "")).strip()
    if not safe_name or len(safe_name) > 255:
        raise ValidationError("Numele fișierului este invalid.")
    extension = Path(safe_name).suffix.lower()
    if extension not in ALLOWED_UPLOADS:
        raise ValidationError("Format neacceptat. Folosește JPG, JPEG, PNG, WEBP sau MP4.")
    media_type, expected_mime, expected_format = ALLOWED_UPLOADS[extension]
    supplied_mime = str(getattr(uploaded_file, "content_type", "") or "").lower()
    if supplied_mime != expected_mime:
        raise ValidationError("Tipul MIME nu corespunde extensiei fișierului.")
    file_size = int(getattr(uploaded_file, "size", 0) or 0)
    if file_size <= 0:
        raise ValidationError("Fișierul este gol.")
    limit_mb = settings.MAX_IMAGE_UPLOAD_MB if media_type == "image" else settings.MAX_VIDEO_UPLOAD_MB
    if file_size > limit_mb * MEBIBYTE:
        raise ValidationError(f"Fișierul depășește limita de {limit_mb} MB.")
    validate_capacity(file_size)
    if media_type == "image":
        _validate_image(uploaded_file, expected_format)
    else:
        _validate_mp4(uploaded_file, file_size)
    return UploadInspection(
        original_filename=safe_name,
        extension=extension,
        media_type=media_type,
        mime_type=expected_mime,
        file_size=file_size,
        checksum=sha256_for_upload(uploaded_file),
    )
