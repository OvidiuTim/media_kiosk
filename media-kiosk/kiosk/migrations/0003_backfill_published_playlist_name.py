from django.db import migrations


def backfill_names(apps, schema_editor):
    PublishedPlaylist = apps.get_model("kiosk", "PublishedPlaylist")
    for snapshot in PublishedPlaylist.objects.select_related("playlist").filter(name=""):
        snapshot.name = snapshot.playlist.name
        snapshot.save(update_fields=["name"])


class Migration(migrations.Migration):
    dependencies = [("kiosk", "0002_publishedplaylist_name")]
    operations = [migrations.RunPython(backfill_names, migrations.RunPython.noop)]

