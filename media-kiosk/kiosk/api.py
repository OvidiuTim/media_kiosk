import uuid

from django.http import HttpResponse
from django.utils import timezone
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Device, PublishedPlaylist
from .services import R2Service


def device_from_request(request):
    key = request.headers.get("X-Device-Key", "").strip()
    if not key:
        return None, Response({"success": False, "error": "Lipsește antetul X-Device-Key."}, status=401)
    try:
        parsed_key = uuid.UUID(key)
        device = Device.objects.select_related("assigned_playlist").get(device_key=parsed_key)
    except (Device.DoesNotExist, ValueError, AttributeError):
        return None, Response({"success": False, "error": "Cheia dispozitivului este invalidă."}, status=401)
    if not device.is_active:
        return None, Response({"success": False, "error": "Dispozitivul este inactiv."}, status=403)
    return device, None


class PlaylistAPIView(APIView):
    def get(self, request):
        device, error = device_from_request(request)
        if error:
            return error
        Device.objects.filter(pk=device.pk).update(last_seen_at=timezone.now())
        playlist = device.assigned_playlist
        if not playlist:
            return Response({"success": False, "error": "Dispozitivul nu are un playlist asociat."}, status=404)
        try:
            snapshot = playlist.published_snapshot
        except PublishedPlaylist.DoesNotExist:
            snapshot = None
        if not playlist.is_active or not snapshot or snapshot.version < 1:
            return Response({"success": False, "error": "Playlistul nu are o versiune publicată activă."}, status=404)
        etag = f'"playlist-{playlist.pk}-v{snapshot.version}"'
        if request.headers.get("If-None-Match") == etag:
            response = HttpResponse(status=304)
            response["ETag"] = etag
            return response
        try:
            r2 = R2Service()
            items = []
            queryset = snapshot.items.select_related("media_asset").filter(
                is_active=True, media_asset__is_active=True
            ).order_by("position")
            for item in queryset:
                items.append({
                    "id": item.id,
                    "media_id": item.media_asset_id,
                    "type": item.media_type,
                    "title": item.title,
                    "url": r2.presign_download(item.r2_object_key),
                    "mime_type": item.mime_type,
                    "file_size": item.file_size,
                    "checksum": item.checksum,
                    "duration_seconds": item.image_duration_seconds if item.media_type == "image" else None,
                    "position": item.position,
                })
        except Exception:
            return Response({"success": False, "error": "Materialele nu pot fi pregătite momentan."}, status=503)
        response = Response({
            "success": True,
            "device": {"id": device.id, "name": device.name},
            "playlist": {
                "id": playlist.id,
                "name": snapshot.name,
                "version": snapshot.version,
                "published_at": snapshot.published_at,
            },
            "items": items,
        })
        response["ETag"] = etag
        response["Cache-Control"] = "private, no-cache"
        return response


class HeartbeatAPIView(APIView):
    def post(self, request):
        device, error = device_from_request(request)
        if error:
            return error
        now = timezone.now()
        Device.objects.filter(pk=device.pk).update(last_seen_at=now)
        return Response({"success": True, "device_id": device.pk, "last_seen_at": now})
