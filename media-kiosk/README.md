# Media Kiosk — etapa 1

Panou web Django în limba română pentru administrarea imaginilor, videoclipurilor, playlisturilor și tabletelor. Fișierele sunt încărcate direct din browser într-un bucket privat Cloudflare R2; serverul păstrează numai metadatele. Aplicația Android nu face parte din această etapă.

Arhitectura de publicare separă draftul (`PlaylistItem`) de ultima versiune publicată (`PublishedPlaylist` și `PublishedPlaylistItem`). La publicare, draftul este copiat într-un snapshot atomic și `published_version` este incrementat. API-ul tabletelor citește exclusiv acel snapshot, așadar editările ulterioare rămân invizibile până la următoarea publicare.

## 1. Instalarea locală

Cerințe: Python 3.11+, un cont Cloudflare și, pentru producție, PostgreSQL.

```bash
cd media-kiosk
python3.11 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

În dezvoltare, dacă `DATABASE_URL` rămâne gol, aplicația folosește automat SQLite.

## 2. Crearea mediului virtual

```bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt
```

Pe Windows PowerShell, activarea se face cu `.venv\Scripts\Activate.ps1`.

## 3. Configurarea `.env`

```bash
cp .env.example .env
```

Completează `DJANGO_SECRET_KEY`, gazdele permise și valorile R2. În producție adaugă și originea HTTPS completă în `DJANGO_CSRF_TRUSTED_ORIGINS`. Pentru PostgreSQL setează, de exemplu:

```env
DATABASE_URL=postgresql://media_kiosk:parola@127.0.0.1:5432/media_kiosk
```

Nu include fișierul `.env` în Git și nu expune credențialele în browser, loguri sau capturi de ecran.

## 4. Rularea migrațiilor

```bash
python manage.py migrate
```

Opțional, creează date demonstrative idempotente:

```bash
python manage.py create_demo_data
```

Obiectele media demo sunt doar metadate și trebuie încărcate separat în R2 sub cheile indicate de comandă.

## 5. Crearea superuserului

```bash
python manage.py createsuperuser
```

Panoul acceptă numai utilizatori autentificați cu `is_staff=True`. Nu există înregistrare publică.

## 6. Pornirea serverului

```bash
python manage.py runserver
```

Deschide `http://127.0.0.1:8000/login/`. Interfața administrativă Django clasică rămâne disponibilă la `/django-admin/`.

## 7. Crearea bucketului Cloudflare R2

În Cloudflare Dashboard, deschide **R2 Object Storage**, creează un bucket și păstrează accesul public dezactivat. Numele bucketului se copiază în `R2_BUCKET_NAME`. Nu configura un domeniu public pentru acest flux.

## 8. Generarea cheilor API R2

În **R2 → Manage R2 API Tokens**, creează un token limitat la bucketul aplicației, cu permisiuni de citire și scriere obiecte. Completează fără ghilimele:

```env
R2_ACCOUNT_ID=
R2_ACCESS_KEY_ID=
R2_SECRET_ACCESS_KEY=
R2_BUCKET_NAME=
R2_ENDPOINT_URL=https://ACCOUNT_ID.r2.cloudflarestorage.com
R2_PRESIGNED_URL_EXPIRATION=86400
MAX_IMAGE_UPLOAD_MB=20
MAX_VIDEO_UPLOAD_MB=1000
```

În producție se recomandă injectarea acestor valori dintr-un secret manager.

## 9. Configurarea CORS

Uploadul este un `PUT` direct din browser către R2. În setările bucketului adaugă o politică CORS; înlocuiește originile cu domeniile reale și nu folosi `*` în producție:

```json
[
  {
    "AllowedOrigins": ["http://127.0.0.1:8000", "http://localhost:8000", "https://kiosk.exemplu.ro"],
    "AllowedMethods": ["GET", "PUT", "HEAD"],
    "AllowedHeaders": ["Content-Type"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

Bucketul rămâne privat. URL-urile de upload și download sunt semnate temporar de server.

## 10. Testarea uploadului

Autentifică-te, deschide **Materiale → Încarcă material**, selectează un JPG/JPEG/PNG/WEBP de cel mult 20 MB sau un MP4 de cel mult 1000 MB. Pentru video: MP4, H.264, AAC, maximum 1080p. Browserul cere URL-ul semnat, face upload direct și confirmă obiectul; serverul verifică dimensiunea și MIME-ul prin `HeadObject` înainte de a crea metadatele.

Dacă uploadul este refuzat de browser, verifică originile CORS, ora sistemului, endpointul R2 și permisiunile tokenului.

## 11. Exemplu `curl` pentru API-ul tabletei

```bash
curl -i http://127.0.0.1:8000/api/kiosk/playlist/ \
  -H "X-Device-Key: UUID-UL-TABLETEI"
```

Pentru cache condiționat:

```bash
curl -i http://127.0.0.1:8000/api/kiosk/playlist/ \
  -H "X-Device-Key: UUID-UL-TABLETEI" \
  -H 'If-None-Match: "playlist-2-v4"'
```

Heartbeat:

```bash
curl -X POST http://127.0.0.1:8000/api/kiosk/heartbeat/ \
  -H "X-Device-Key: UUID-UL-TABLETEI"
```

## 12. Instrucțiuni pentru rularea testelor

Testele nu contactează Cloudflare; serviciul R2 este mock-uit.

```bash
python manage.py check
python manage.py test
```

Pentru a verifica dacă modelele sunt sincronizate cu migrațiile:

```bash
python manage.py makemigrations --check --dry-run
```

## 13. Pașii recomandați pentru deploy cu Gunicorn și Nginx

1. Creează un utilizator Linux dedicat și copiază aplicația într-un director fără drepturi de scriere publică.
2. Creează mediul virtual, instalează `requirements.txt` și configurează `.env` cu `DJANGO_DEBUG=False`, un `DJANGO_SECRET_KEY` puternic, `DJANGO_ALLOWED_HOSTS`, PostgreSQL și R2.
3. Rulează `python manage.py migrate` și `python manage.py collectstatic --noinput`.
4. Pornește Gunicorn prin systemd, de exemplu: `gunicorn media_kiosk.wsgi:application --workers 3 --bind 127.0.0.1:8001`.
5. Configurează Nginx ca reverse proxy către `127.0.0.1:8001`, servește `/static/`, limitează dimensiunea cererilor normale și setează headerele proxy corecte.
6. Activează HTTPS cu un certificat valid, apoi setează `CSRF_TRUSTED_ORIGINS`, `SECURE_SSL_REDIRECT=True`, cookie-uri secure și HSTS în configurația Django de producție.
7. Restricționează firewallul, protejează fișierul de mediu, rotește cheile R2 și configurează backup-uri PostgreSQL și monitorizarea serviciilor.

Nu este necesară mărirea `client_max_body_size` la 1 GB pentru uploadurile media: fișierele merg direct din browser în R2.

## Endpointuri principale

- Panou: `/`, `/media/`, `/playlists/`, `/devices/`
- API tabletă: `GET /api/kiosk/playlist/`
- Heartbeat: `POST /api/kiosk/heartbeat/`
- Autentificare tabletă: antetul `X-Device-Key`
