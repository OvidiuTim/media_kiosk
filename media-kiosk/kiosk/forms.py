from django import forms

from .models import Device, MediaAsset, Playlist


class BootstrapFormMixin:
    def style_fields(self):
        for field in self.fields.values():
            css = "form-check-input" if isinstance(field.widget, forms.CheckboxInput) else "form-control"
            if isinstance(field.widget, forms.Select):
                css = "form-select"
            field.widget.attrs["class"] = css


class PlaylistForm(BootstrapFormMixin, forms.ModelForm):
    class Meta:
        model = Playlist
        fields = ["name", "description", "is_active"]
        widgets = {"description": forms.Textarea(attrs={"rows": 3})}

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.style_fields()


class DeviceForm(BootstrapFormMixin, forms.ModelForm):
    class Meta:
        model = Device
        fields = ["name", "assigned_playlist", "is_active"]

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.style_fields()


class MediaAssetForm(BootstrapFormMixin, forms.ModelForm):
    class Meta:
        model = MediaAsset
        fields = ["title", "is_active"]

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.style_fields()

