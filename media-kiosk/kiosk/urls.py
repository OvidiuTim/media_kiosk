from django.urls import path

from . import views
from .api import HeartbeatAPIView, PlaylistAPIView

urlpatterns = [
    path("", views.dashboard, name="dashboard"),
    path("materials/", views.media_list, name="media_list"),
    path("materials/upload/", views.media_upload, name="media_upload"),
    path("materials/<int:pk>/edit/", views.media_edit, name="media_edit"),
    path("materials/<int:pk>/delete/", views.media_delete, name="media_delete"),
    path("playlists/", views.playlist_list, name="playlist_list"),
    path("playlists/new/", views.playlist_create, name="playlist_create"),
    path("playlists/<int:pk>/edit/", views.playlist_edit, name="playlist_edit"),
    path("playlists/<int:pk>/add-item/", views.playlist_add_item, name="playlist_add_item"),
    path("playlists/<int:pk>/reorder/", views.playlist_reorder, name="playlist_reorder"),
    path("playlists/<int:pk>/items/<int:item_pk>/", views.playlist_item_update, name="playlist_item_update"),
    path("playlists/<int:pk>/items/<int:item_pk>/delete/", views.playlist_item_delete, name="playlist_item_delete"),
    path("playlists/<int:pk>/publish/", views.playlist_publish, name="playlist_publish"),
    path("playlists/<int:pk>/preview/", views.playlist_preview, name="playlist_preview"),
    path("devices/", views.device_list, name="device_list"),
    path("devices/new/", views.device_form, name="device_create"),
    path("devices/<int:pk>/edit/", views.device_form, name="device_edit"),
    path("api/kiosk/playlist/", PlaylistAPIView.as_view(), name="api_playlist"),
    path("api/kiosk/heartbeat/", HeartbeatAPIView.as_view(), name="api_heartbeat"),
]
