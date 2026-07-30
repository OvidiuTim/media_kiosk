# Media Kiosk — etapa 1
# superuser admin admin

Panou web Django în limba română pentru administrarea imaginilor, videoclipurilor, playlisturilor și tabletelor. Fișierele sunt stocate pe discul serverului, iar baza de date păstrează numai calea și metadatele. Aplicația Android nu face parte din această etapă.

Draftul (`PlaylistItem`) este separat de ultima versiune publicată (`PublishedPlaylist` și `PublishedPlaylistItem`). API-ul tabletelor citește exclusiv snapshot-ul publicat. URL-urile media sunt stabile, iar checksum-ul SHA-256 permite aplicației să recunoască fișierele deja descărcate.

## Instalare locală

Cerințe: Python 3.11+ și, pentru producție, PostgreSQL și Nginx.

```bash
cd /Users/xux/Documents/GitHub/media_kiosk/media-kiosk
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt
cp .env.example .env
python manage.py migrate
python manage.py createsuperuser
python manage.py runserver 127.0.0.1:8010
```

Deschide `http://127.0.0.1:8010/login/`. Biblioteca este la `/materials/`; prefixul `/media/` este rezervat exclusiv fișierelor și poate fi livrat direct de Nginx în producție.

Pentru date demonstrative locale:

```bash
python manage.py create_demo_data
```

Comanda creează o imagine reală în `MEDIA_ROOT`, un playlist publicat și o tabletă demonstrativă.

## Configurarea `.env`

Configurația minimă de dezvoltare:

```env
DJANGO_SECRET_KEY=schimba-ma-cu-o-valoare-lunga-si-aleatoare
DJANGO_DEBUG=True
DJANGO_ALLOWED_HOSTS=localhost,127.0.0.1
DJANGO_CSRF_TRUSTED_ORIGINS=
DATABASE_URL=

MEDIA_STORAGE_BACKEND=local
MEDIA_ROOT=
MEDIA_URL=/media/
MAX_IMAGE_UPLOAD_MB=20
MAX_VIDEO_UPLOAD_MB=1000
MAX_TOTAL_MEDIA_GB=20
MIN_FREE_DISK_GB=2
```

Dacă `MEDIA_ROOT` este gol, se folosește automat `PROJECT_ROOT/media/`. În producție setează o cale absolută:

```env
MEDIA_ROOT=/var/www/media-kiosk/media
MEDIA_URL=/media/
DATABASE_URL=postgresql://media_kiosk:PAROLA@127.0.0.1:5432/media_kiosk
```

`MAX_TOTAL_MEDIA_GB` limitează întreaga bibliotecă, iar `MIN_FREE_DISK_GB` păstrează o rezervă pe partiție. Fișierele mai mari de aproximativ 2,5 MB folosesc handlerul temporar standard Django și nu sunt încărcate integral în RAM.

### Optimizarea automată a videoclipurilor

FFmpeg și ffprobe trebuie instalate numai pe server, nu pe calculatorul utilizatorului:

```bash
sudo apt update
sudo apt install ffmpeg
ffmpeg -version
ffprobe -version
```

Adaugă în `.env`:

```env
FFMPEG_BINARY=ffmpeg
FFPROBE_BINARY=ffprobe
VIDEO_TRANSCODING_ENABLED=True
VIDEO_MAX_WIDTH=1920
VIDEO_MAX_HEIGHT=1080
VIDEO_MAX_FPS=30
VIDEO_CRF=23
VIDEO_MAX_BITRATE=5M
VIDEO_AUDIO_BITRATE=128k
VIDEO_PROCESSING_STALE_MINUTES=30
VIDEO_QUEUE_SLEEP_SECONDS=5
```

Pentru serverul de aproximativ 25 GB, valorile recomandate sunt `MAX_TOTAL_MEDIA_GB=8` și `MIN_FREE_DISK_GB=5`. Limita include sursele temporare, outputurile `.part.mp4` și fișierele finale.

După upload, imaginile devin imediat `ready`. Videoclipurile trec prin `queued → processing → ready`; sursa este eliminată numai după verificarea rezultatului și înlocuirea atomică. La eroare, sursa rămâne disponibilă pentru **Reîncearcă**. Dacă FFmpeg lipsește, uploadul rămâne funcțional, videoclipul este marcat cu eroare clară, iar imaginile nu sunt afectate.

Pentru o singură procesare locală:

```bash
python manage.py process_media_queue
```

Pentru worker continuu:

```bash
python manage.py process_media_queue --watch --sleep 5
```

Workerul folosește o blocare persistentă în baza de date și procesează global un singur videoclip. Joburile rămase în `processing` după un restart sunt recuperate după `VIDEO_PROCESSING_STALE_MINUTES`.

## Upload și validare

Pagina **Materiale → Încarcă material** trimite fișierul către Django prin `XMLHttpRequest`, cu CSRF și progres vizual. Serverul verifică:

- extensia și MIME-ul pentru JPG, JPEG, PNG, WEBP și MP4;
- conținutul imaginilor cu Pillow;
- structura ISO BMFF și blocul `ftyp` pentru MP4;
- limita individuală, limita totală și spațiul liber;
- checksum-ul SHA-256, calculat în chunks.

Fișierul primește un nume UUID, de exemplu:

```text
media/kiosk/images/2026/07/2dc52e77f38749e7ba36135f28ffb70d.png
media/kiosk/videos/2026/07/5d63171ce2d0441d93f40b234a03c207.mp4
```

## API-ul tabletei

```bash
curl -i http://127.0.0.1:8010/api/kiosk/playlist/ \
  -H "X-Device-Key: UUID-UL-TABLETEI"
```

Cache condiționat:

```bash
curl -i http://127.0.0.1:8010/api/kiosk/playlist/ \
  -H "X-Device-Key: UUID-UL-TABLETEI" \
  -H 'If-None-Match: "playlist-2-v4"'
```

Heartbeat:

```bash
curl -X POST http://127.0.0.1:8010/api/kiosk/heartbeat/ \
  -H "X-Device-Key: UUID-UL-TABLETEI"
```

URL-ul fiecărui material este construit din request; domeniul nu este hardcodat.

## Teste

Testele folosesc un `TemporaryDirectory` și nu scriu în directorul media real.

```bash
python manage.py check
python manage.py makemigrations --check --dry-run
python manage.py test
```

## Pregătirea directoarelor în producție

Exemplul folosește utilizatorul de serviciu `media-kiosk` pentru Gunicorn și grupul `www-data` pentru Nginx:

```bash
sudo useradd --system --home /var/www/media-kiosk --shell /usr/sbin/nologin media-kiosk
sudo install -d -o media-kiosk -g www-data -m 0750 /var/www/media-kiosk
sudo install -d -o media-kiosk -g www-data -m 2770 /var/www/media-kiosk/media
sudo install -d -o media-kiosk -g www-data -m 0750 /var/www/media-kiosk/staticfiles
```

Gunicorn și workerul `deploy:www-data` trebuie să poată scrie în `MEDIA_ROOT`; Nginx trebuie să poată traversa directoarele și citi fișierele. Django creează fișiere cu `0640` și directoare cu `0750`. Directorul rădăcină media are grupul `www-data`, bit setgid și drept de scriere pentru grup. Nu folosi permisiuni `777`.

Verifică identitatea proceselor și permisiunile:

```bash
ps -eo user,group,cmd | grep -E 'gunicorn|nginx'
namei -l /var/www/media-kiosk/media
sudo -u media-kiosk test -w /var/www/media-kiosk/media
sudo -u deploy test -w /var/www/media-kiosk/media
sudo -u www-data test -r /var/www/media-kiosk/media
```

## Workerul systemd pentru transcodare

Fișierul complet este inclus în `deploy/media-kiosk-transcoder.service`. Instalează-l separat de serviciul Gunicorn și de orice `gunicorn-task.service` sau `gunicorn-taskapp.service`:

```bash
sudo cp deploy/media-kiosk-transcoder.service /etc/systemd/system/media-kiosk-transcoder.service
sudo systemctl daemon-reload
sudo systemctl enable --now media-kiosk-transcoder.service
sudo systemctl status media-kiosk-transcoder.service
journalctl -u media-kiosk-transcoder.service -f
```

Serviciul rulează cu `User=deploy`, `Group=www-data`, `Nice=15`, `CPUQuota=40%` și `IOSchedulingClass=idle`. Comanda `ExecStart` este:

```text
/home/deploy/media-kiosk/.venv/bin/python /home/deploy/media-kiosk/media-kiosk/manage.py process_media_queue --watch --sleep 5
```

Nu porni workerul din Gunicorn și nu adăuga mai multe instanțe. Blocarea din baza de date împiedică procesarea simultană chiar dacă serviciul este pornit accidental de două ori.

### Migrare și activare în producție

```bash
cd /home/deploy/media-kiosk/media-kiosk
source /home/deploy/media-kiosk/.venv/bin/activate
pip install -r requirements.txt
python manage.py migrate
python manage.py check
python manage.py collectstatic --noinput
sudo systemctl restart media-kiosk.service
sudo systemctl restart media-kiosk-transcoder.service
```

Migrarea păstrează materialele și snapshoturile existente. Imaginile și videoclipurile existente sunt marcate inițial `ready`; pentru videoclipurile vechi apare acțiunea **Optimizează videoclipul**. În timpul reoptimizării, versiunea publicată existentă rămâne disponibilă. După succes, snapshoturile afectate primesc noul fișier, checksum și dimensiune, iar versiunea/ETag-ul playlistului este incrementată automat.

## Gunicorn prin systemd

Exemplu `/etc/systemd/system/media-kiosk.service`:

```ini
[Unit]
Description=Media Kiosk Django
After=network.target postgresql.service

[Service]
User=media-kiosk
Group=www-data
WorkingDirectory=/var/www/media-kiosk/app
EnvironmentFile=/var/www/media-kiosk/app/.env
ExecStart=/var/www/media-kiosk/app/.venv/bin/gunicorn media_kiosk.wsgi:application --workers 3 --bind 127.0.0.1:8001 --timeout 3600 --access-logfile - --error-logfile -
Restart=on-failure
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Aplică migrațiile și fișierele statice înainte de restart:

```bash
sudo -u media-kiosk /var/www/media-kiosk/app/.venv/bin/python /var/www/media-kiosk/app/manage.py migrate
sudo -u media-kiosk /var/www/media-kiosk/app/.venv/bin/python /var/www/media-kiosk/app/manage.py collectstatic --noinput
sudo systemctl daemon-reload
sudo systemctl enable --now media-kiosk
```

## Configurare Nginx completă

Nginx deservește media direct de pe disc. Handlerul static Nginx suportă nativ Range Requests, `206 Partial Content`, seek și reluarea descărcărilor; videoclipurile nu trec prin Gunicorn.

```nginx
server {
    listen 443 ssl http2;
    server_name kiosk.exemplu.ro;

    client_max_body_size 1024M;
    client_body_timeout 3600s;
    send_timeout 3600s;

    location ^~ /media/ {
        alias /var/www/media-kiosk/media/;
        access_log off;
        add_header Accept-Ranges "bytes" always;
        add_header Cache-Control "public, max-age=86400" always;
    }

    location ^~ /static/ {
        alias /var/www/media-kiosk/staticfiles/;
        access_log off;
        add_header Cache-Control "public, max-age=604800" always;
    }

    location / {
        proxy_pass http://127.0.0.1:8001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 60s;
        proxy_send_timeout 3600s;
        proxy_read_timeout 3600s;
    }
}
```

Păstrează `include /etc/nginx/mime.types;` în blocul `http` al configurației globale, astfel încât MP4 să fie livrat ca `video/mp4`. Buffering-ul implicit Nginx folosește fișiere temporare pentru requesturi mari; partiția configurată prin `client_body_temp_path` trebuie să aibă suficient spațiu.

Verifică și reîncarcă:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

## Verificarea Range Requests și a spațiului

```bash
curl -I https://kiosk.exemplu.ro/media/kiosk/videos/2026/07/UUID.mp4
curl -sS -D - -o /dev/null \
  -H 'Range: bytes=0-1023' \
  https://kiosk.exemplu.ro/media/kiosk/videos/2026/07/UUID.mp4
```

Al doilea răspuns trebuie să conțină `HTTP/1.1 206 Partial Content`, `Content-Range: bytes 0-1023/...`, `Content-Length: 1024` și `Accept-Ranges: bytes`.

Verifică spațiul și dimensiunea bibliotecii:

```bash
df -h /var/www/media-kiosk/media
du -sh /var/www/media-kiosk/media
```

## Backup

Baza PostgreSQL conține metadatele, playlisturile și dispozitivele, nu conținutul video:

```bash
sudo -u postgres pg_dump -Fc media_kiosk > media_kiosk_$(date +%F).dump
```

Pentru SQLite, oprește temporar Gunicorn și copiază `db.sqlite3`. Fișierele media se salvează separat; poți exclude videoclipurile dacă politica de backup permite reîncărcarea lor:

```bash
rsync -a --exclude='kiosk/videos/' /var/www/media-kiosk/media/ /backup/media-kiosk-media/
```

Testează periodic restaurarea bazei și păstrează cel puțin inventarul căilor și checksum-urilor chiar dacă videoclipurile mari nu intră în fiecare backup.
