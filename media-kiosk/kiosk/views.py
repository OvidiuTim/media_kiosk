import json

from django.contrib import messages
from django.contrib.admin.views.decorators import staff_member_required
from django.core import signing
from django.core.exceptions import ImproperlyConfigured, ValidationError
from django.db import IntegrityError, transaction
from django.db.models import Max, Q
from django.http import JsonResponse
from django.shortcuts import get_object_or_404, redirect, render
from django.utils import timezone
from django.views.decorators.http import require_GET, require_http_methods, require_POST

from .forms import DeviceForm, MediaAssetForm, PlaylistForm
from .models import Device, MediaAsset, Playlist, PlaylistItem
from .services import (
    R2Service, generate_object_key, make_upload_token, read_upload_token,
    valid_checksum, validate_upload,
)


def json_body(request):
    try:
        return json.loads(request.body or "{}")
    except json.JSONDecodeError:
        raise ValidationError("Conținut JSON invalid.")


def preview_url(object_key):
    try:
        return R2Service().presign_download(object_key)
    except Exception:
        return ""


@staff_member_required(login_url="login")
def dashboard(request):
    stale_before = timezone.now() - timezone.timedelta(minutes=5)
    return render(request, "kiosk/dashboard.html", {
        "image_count": MediaAsset.objects.filter(media_type="image").count(),
        "video_count": MediaAsset.objects.filter(media_type="video").count(),
        "playlist_count": Playlist.objects.count(),
        "device_count": Device.objects.count(),
        "active_device_count": Device.objects.filter(is_active=True).count(),
        "stale_device_count": Device.objects.filter(is_active=True).filter(
            Q(last_seen_at__lt=stale_before) | Q(last_seen_at__isnull=True)
        ).count(),
        "recent_devices": Device.objects.select_related("assigned_playlist").order_by("-last_seen_at")[:6],
    })


@staff_member_required(login_url="login")
def media_list(request):
    assets = MediaAsset.objects.all()
    query = request.GET.get("q", "").strip()
    media_type = request.GET.get("type", "").strip()
    if query:
        assets = assets.filter(title__icontains=query)
    if media_type in {"image", "video"}:
        assets = assets.filter(media_type=media_type)
    rows = []
    for asset in assets:
        rows.append({"asset": asset, "preview_url": preview_url(asset.r2_object_key)})
    return render(request, "kiosk/media_list.html", {"rows": rows, "query": query, "media_type": media_type})


@staff_member_required(login_url="login")
@require_GET
def media_upload(request):
    return render(request, "kiosk/media_upload.html")


@staff_member_required(login_url="login")
@require_POST
def upload_presign(request):
    try:
        data = json_body(request)
        validated = validate_upload(data.get("filename"), data.get("mime_type"), data.get("file_size"), data.get("title"))
        object_key = generate_object_key(validated.extension)
        url = R2Service().presign_upload(object_key, validated.mime_type)
        return JsonResponse({
            "success": True,
            "upload_url": url,
            "object_key": object_key,
            "upload_token": make_upload_token(validated, object_key),
            "headers": {"Content-Type": validated.mime_type},
        })
    except ValidationError as exc:
        return JsonResponse({"success": False, "error": exc.messages[0]}, status=400)
    except ImproperlyConfigured:
        return JsonResponse({"success": False, "error": "Cloudflare R2 nu este configurat."}, status=503)
    except Exception:
        return JsonResponse({"success": False, "error": "URL-ul de upload nu a putut fi generat."}, status=502)


@staff_member_required(login_url="login")
@require_POST
def upload_confirm(request):
    try:
        data = json_body(request)
        payload = read_upload_token(data.get("upload_token", ""))
        head = R2Service().head(payload["object_key"])
        if int(head.get("ContentLength", -1)) != int(payload["file_size"]):
            raise ValidationError("Dimensiunea obiectului încărcat nu corespunde.")
        if head.get("ContentType") != payload["mime_type"]:
            raise ValidationError("Tipul obiectului încărcat nu corespunde.")
        checksum = valid_checksum(data.get("checksum")) or head.get("ChecksumSHA256")
        asset = MediaAsset.objects.create(
            title=payload["title"], media_type=payload["media_type"],
            r2_object_key=payload["object_key"], original_filename=payload["original_filename"],
            mime_type=payload["mime_type"], file_size=payload["file_size"], checksum=checksum,
        )
        return JsonResponse({"success": True, "asset_id": asset.pk, "redirect_url": "/media/"})
    except signing.BadSignature:
        return JsonResponse({"success": False, "error": "Confirmarea uploadului a expirat sau este invalidă."}, status=400)
    except ValidationError as exc:
        return JsonResponse({"success": False, "error": exc.messages[0]}, status=400)
    except Exception:
        return JsonResponse({"success": False, "error": "Uploadul nu a putut fi confirmat."}, status=502)


@staff_member_required(login_url="login")
@require_http_methods(["GET", "POST"])
def media_edit(request, pk):
    asset = get_object_or_404(MediaAsset, pk=pk)
    form = MediaAssetForm(request.POST or None, instance=asset)
    if request.method == "POST" and form.is_valid():
        form.save()
        messages.success(request, "Materialul a fost actualizat.")
        return redirect("media_list")
    return render(request, "kiosk/form.html", {"form": form, "title": "Editează materialul", "back_url": "media_list"})


@staff_member_required(login_url="login")
@require_http_methods(["GET", "POST"])
def media_delete(request, pk):
    asset = get_object_or_404(MediaAsset, pk=pk)
    usage_count = asset.playlist_items.count() + asset.published_items.count()
    if request.method == "POST":
        remove_everywhere = request.POST.get("remove_everywhere") == "1"
        if usage_count and not remove_everywhere:
            messages.error(request, "Materialul este folosit. Confirmă eliminarea din toate playlisturile.")
        else:
            try:
                R2Service().delete(asset.r2_object_key)
            except Exception:
                messages.error(request, "Fișierul nu a putut fi șters din R2. Înregistrarea a fost păstrată.")
                return redirect("media_delete", pk=asset.pk)
            with transaction.atomic():
                if remove_everywhere:
                    asset.playlist_items.all().delete()
                    asset.published_items.all().delete()
                asset.delete()
            messages.success(request, "Materialul a fost șters.")
            return redirect("media_list")
    return render(request, "kiosk/media_delete.html", {"asset": asset, "usage_count": usage_count})


@staff_member_required(login_url="login")
def playlist_list(request):
    return render(request, "kiosk/playlist_list.html", {"playlists": Playlist.objects.prefetch_related("items", "devices")})


@staff_member_required(login_url="login")
@require_http_methods(["GET", "POST"])
def playlist_create(request):
    form = PlaylistForm(request.POST or None)
    if request.method == "POST" and form.is_valid():
        playlist = form.save()
        messages.success(request, "Playlistul a fost creat. Adaugă materiale și publică-l când este gata.")
        return redirect("playlist_edit", pk=playlist.pk)
    return render(request, "kiosk/form.html", {"form": form, "title": "Playlist nou", "back_url": "playlist_list"})


@staff_member_required(login_url="login")
@require_http_methods(["GET", "POST"])
def playlist_edit(request, pk):
    playlist = get_object_or_404(Playlist, pk=pk)
    form = PlaylistForm(request.POST or None, instance=playlist)
    if request.method == "POST" and form.is_valid():
        form.save()
        messages.success(request, "Detaliile draftului au fost salvate.")
        return redirect("playlist_edit", pk=pk)
    return render(request, "kiosk/playlist_editor.html", {
        "playlist": playlist,
        "form": form,
        "items": playlist.items.select_related("media_asset").order_by("position"),
        "available_assets": MediaAsset.objects.filter(is_active=True).order_by("title"),
    })


@staff_member_required(login_url="login")
@require_POST
def playlist_add_item(request, pk):
    playlist = get_object_or_404(Playlist, pk=pk)
    try:
        data = json_body(request)
        asset = MediaAsset.objects.get(pk=data.get("media_asset_id"), is_active=True)
        with transaction.atomic():
            locked = Playlist.objects.select_for_update().get(pk=playlist.pk)
            position = (locked.items.aggregate(m=Max("position"))["m"] or 0) + 1
            item = PlaylistItem.objects.create(playlist=locked, media_asset=asset, position=position)
        return JsonResponse({"success": True, "item_id": item.pk})
    except (ValidationError, MediaAsset.DoesNotExist):
        return JsonResponse({"success": False, "error": "Materialul selectat este invalid."}, status=400)


@staff_member_required(login_url="login")
@require_POST
def playlist_reorder(request, pk):
    playlist = get_object_or_404(Playlist, pk=pk)
    try:
        ordered_ids = [int(value) for value in json_body(request).get("item_ids", [])]
        with transaction.atomic():
            items = list(PlaylistItem.objects.select_for_update().filter(playlist=playlist))
            current_ids = {item.id for item in items}
            if len(ordered_ids) != len(current_ids) or set(ordered_ids) != current_ids:
                raise ValidationError("Lista de elemente este incompletă.")
            by_id = {item.id: item for item in items}
            offset = len(items) + 1000
            for item in items:
                item.position += offset
            PlaylistItem.objects.bulk_update(items, ["position"])
            for position, item_id in enumerate(ordered_ids, start=1):
                by_id[item_id].position = position
            PlaylistItem.objects.bulk_update(items, ["position"])
        return JsonResponse({"success": True})
    except (ValidationError, ValueError) as exc:
        message = exc.messages[0] if isinstance(exc, ValidationError) else "Ordine invalidă."
        return JsonResponse({"success": False, "error": message}, status=400)


@staff_member_required(login_url="login")
@require_POST
def playlist_item_update(request, pk, item_pk):
    item = get_object_or_404(PlaylistItem, pk=item_pk, playlist_id=pk)
    try:
        data = json_body(request)
        if "is_active" in data:
            item.is_active = bool(data["is_active"])
        if item.media_asset.media_type == "image" and "image_duration_seconds" in data:
            duration = int(data["image_duration_seconds"])
            if duration < 1 or duration > 86400:
                raise ValueError
            item.image_duration_seconds = duration
        item.save(update_fields=["is_active", "image_duration_seconds", "updated_at"])
        return JsonResponse({"success": True})
    except (TypeError, ValueError):
        return JsonResponse({"success": False, "error": "Durata trebuie să fie între 1 și 86400 secunde."}, status=400)


@staff_member_required(login_url="login")
@require_POST
def playlist_item_delete(request, pk, item_pk):
    item = get_object_or_404(PlaylistItem, pk=item_pk, playlist_id=pk)
    with transaction.atomic():
        Playlist.objects.select_for_update().get(pk=pk)
        item.delete()
        items = list(PlaylistItem.objects.filter(playlist_id=pk).order_by("position"))
        offset = len(items) + 1000
        for remaining in items:
            remaining.position += offset
        PlaylistItem.objects.bulk_update(items, ["position"])
        for position, remaining in enumerate(items, start=1):
            remaining.position = position
        PlaylistItem.objects.bulk_update(items, ["position"])
    return JsonResponse({"success": True})


@staff_member_required(login_url="login")
@require_POST
def playlist_publish(request, pk):
    playlist = get_object_or_404(Playlist, pk=pk)
    playlist.publish()
    messages.success(request, f"Playlist publicat: versiunea {playlist.published_version}.")
    return redirect("playlist_edit", pk=pk)


@staff_member_required(login_url="login")
def playlist_preview(request, pk):
    playlist = get_object_or_404(Playlist, pk=pk)
    try:
        snapshot = playlist.published_snapshot
        items = [{
            "type": item.media_type,
            "title": item.title,
            "url": preview_url(item.r2_object_key),
            "duration": item.image_duration_seconds,
        } for item in snapshot.items.filter(is_active=True, media_asset__is_active=True).order_by("position")]
    except Exception:
        snapshot, items = None, []
    return render(request, "kiosk/playlist_preview.html", {"playlist": playlist, "snapshot": snapshot, "preview_items": items})


@staff_member_required(login_url="login")
def device_list(request):
    stale_before = timezone.now() - timezone.timedelta(minutes=5)
    return render(request, "kiosk/device_list.html", {
        "devices": Device.objects.select_related("assigned_playlist"), "stale_before": stale_before,
    })


@staff_member_required(login_url="login")
@require_http_methods(["GET", "POST"])
def device_form(request, pk=None):
    device = get_object_or_404(Device, pk=pk) if pk else None
    form = DeviceForm(request.POST or None, instance=device)
    if request.method == "POST" and form.is_valid():
        saved = form.save()
        messages.success(request, "Dispozitivul a fost salvat.")
        return redirect("device_edit", pk=saved.pk)
    return render(request, "kiosk/device_form.html", {"form": form, "device": device})
