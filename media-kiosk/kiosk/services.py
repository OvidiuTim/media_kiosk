import os
import re
import uuid
from dataclasses import dataclass
from pathlib import Path

import boto3
from botocore.config import Config
from django.conf import settings
from django.core import signing
from django.core.exceptions import ImproperlyConfigured, ValidationError
from django.utils import timezone


UPLOAD_TOKEN_SALT = "media-kiosk-r2-upload"
ALLOWED_UPLOADS = {
    ".jpg": ("image", {"image/jpeg"}),
    ".jpeg": ("image", {"image/jpeg"}),
    ".png": ("image", {"image/png"}),
    ".webp": ("image", {"image/webp"}),
    ".mp4": ("video", {"video/mp4"}),
}


@dataclass(frozen=True)
class ValidatedUpload:
    original_filename: str
    extension: str
    media_type: str
    mime_type: str
    file_size: int
    title: str


def validate_upload(filename, mime_type, file_size, title=""):
    safe_name = os.path.basename(str(filename or "")).strip()
    if not safe_name or len(safe_name) > 255:
        raise ValidationError("Numele fișierului este invalid.")
    extension = Path(safe_name).suffix.lower()
    if extension not in ALLOWED_UPLOADS:
        raise ValidationError("Format neacceptat. Folosește JPG, JPEG, PNG, WEBP sau MP4.")
    media_type, allowed_mimes = ALLOWED_UPLOADS[extension]
    if mime_type not in allowed_mimes:
        raise ValidationError("Tipul MIME nu corespunde extensiei fișierului.")
    try:
        size = int(file_size)
    except (TypeError, ValueError):
        raise ValidationError("Dimensiunea fișierului este invalidă.")
    if size <= 0:
        raise ValidationError("Fișierul este gol.")
    limit_mb = settings.MAX_IMAGE_UPLOAD_MB if media_type == "image" else settings.MAX_VIDEO_UPLOAD_MB
    if size > limit_mb * 1024 * 1024:
        raise ValidationError(f"Fișierul depășește limita de {limit_mb} MB.")
    clean_title = str(title or Path(safe_name).stem).strip()[:200]
    if not clean_title:
        raise ValidationError("Titlul este obligatoriu.")
    return ValidatedUpload(safe_name, extension, media_type, mime_type, size, clean_title)


def generate_object_key(extension):
    now = timezone.now()
    return f"media/{now:%Y/%m}/{uuid.uuid4().hex}{extension}"


class R2Service:
    def __init__(self):
        endpoint = settings.R2_ENDPOINT_URL or (
            f"https://{settings.R2_ACCOUNT_ID}.r2.cloudflarestorage.com" if settings.R2_ACCOUNT_ID else ""
        )
        required = [settings.R2_ACCESS_KEY_ID, settings.R2_SECRET_ACCESS_KEY, settings.R2_BUCKET_NAME, endpoint]
        if not all(required):
            raise ImproperlyConfigured("Configurarea Cloudflare R2 este incompletă.")
        self.bucket = settings.R2_BUCKET_NAME
        self.client = boto3.client(
            "s3",
            endpoint_url=endpoint,
            aws_access_key_id=settings.R2_ACCESS_KEY_ID,
            aws_secret_access_key=settings.R2_SECRET_ACCESS_KEY,
            region_name="auto",
            config=Config(signature_version="s3v4"),
        )

    def presign_upload(self, object_key, mime_type):
        return self.client.generate_presigned_url(
            "put_object",
            Params={"Bucket": self.bucket, "Key": object_key, "ContentType": mime_type},
            ExpiresIn=settings.R2_PRESIGNED_URL_EXPIRATION,
        )

    def presign_download(self, object_key):
        return self.client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": object_key},
            ExpiresIn=settings.R2_PRESIGNED_URL_EXPIRATION,
        )

    def head(self, object_key):
        return self.client.head_object(Bucket=self.bucket, Key=object_key)

    def delete(self, object_key):
        self.client.delete_object(Bucket=self.bucket, Key=object_key)


def make_upload_token(validated, object_key):
    return signing.dumps({
        "object_key": object_key,
        "original_filename": validated.original_filename,
        "media_type": validated.media_type,
        "mime_type": validated.mime_type,
        "file_size": validated.file_size,
        "title": validated.title,
    }, salt=UPLOAD_TOKEN_SALT, compress=True)


def read_upload_token(token):
    return signing.loads(
        token,
        salt=UPLOAD_TOKEN_SALT,
        max_age=settings.R2_PRESIGNED_URL_EXPIRATION,
    )


def valid_checksum(value):
    if not value:
        return None
    value = str(value).lower()
    return value if re.fullmatch(r"[a-f0-9]{64}", value) else None

