# Media Kiosk pentru Android

Aplicație Android nativă pentru tablete dedicate, conectată la backendul Django Media Kiosk. Proiectul folosește Kotlin, Android Views/XML, ViewBinding, Media3 ExoPlayer, Retrofit/OkHttp, coroutines, SharedPreferences și Glide. Nu conține o cheie de dispozitiv sau materiale hardcodate.

## Cerințe de build

- Android Studio cu JDK 17 inclus;
- Android SDK Platform 34 și Build Tools 34;
- `compileSdk = 34`, `targetSdk = 34`, `minSdk = 22`;
- conexiune la internet la primul build, pentru dependențele Gradle.

Import: în Android Studio se alege **Open** și directorul `android-app`, apoi se așteaptă terminarea Gradle Sync. Nu este necesar un fișier `local.properties` în repository; Android Studio îl generează local.

Din terminal, pe macOS cu Android Studio instalat în locația standard:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK-ul debug este generat în:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Contractul backend folosit

Aplicația folosește exact endpointurile existente:

- `GET /api/kiosk/playlist/`;
- `POST /api/kiosk/heartbeat/`;
- header de autentificare `X-Device-Key: UUID`;
- `If-None-Match` la sincronizare și păstrarea playlistului local la `304`;
- tratare separată pentru `401`, `403`, `404`, erori HTTPS și timeout.

Răspunsul playlistului este validat înainte de folosire: `device`, `playlist`, versiune publicată și elementele reale (`id`, `media_id`, `type`, `url`, `mime_type`, `file_size`, `checksum`, `duration_seconds`, `position`). Sunt acceptate numai `image` și `video`, URL-uri HTTPS de pe același host și port ca serverul configurat, dimensiuni rezonabile, poziții unice și checksum SHA-256 valid când este prezent.

Cheia nu este pusă în URL, query, BuildConfig sau loguri. Este salvată numai în SharedPreferences private ale aplicației. PIN-ul este stocat ca hash SHA-256 iterat cu salt aleator; după trei încercări greșite accesul este blocat 30 de secunde.

## Configurare și utilizare

La prima pornire se introduc:

1. adresa serverului, implicit `https://kiosk.dmxconstruction.ro`;
2. UUID-ul copiat din panoul Django la crearea tabletei;
3. limita cache: 256 MB, 512 MB, 1 GB sau 2 GB;
4. orientarea: peisaj, portret sau automată;
5. un PIN administrativ ales local, de minimum patru cifre.

Apasă **Testează conexiunea**. Configurația poate fi salvată numai după ce serverul confirmă cheia; testul afișează numele dispozitivului și playlistul. Testarea nu suprascrie playlistul activ înainte de apăsarea butonului **Salvează și pornește**.

Playerul rulează fullscreen, fără controale, menține ecranul pornit și reaplică immersive mode. Imaginile folosesc `FIT_CENTER` și durata API, videoclipurile sunt redate integral, iar lista se repetă. O versiune nouă este pregătită în cache și devine activă doar la capătul materialului curent. Sincronizarea și heartbeatul rulează la 60 de secunde numai cât activitatea este pornită, cu backoff până la cinci minute.

Cache-ul privat este în `filesDir/media_cache/`. Descărcările sunt scrise în `.part`, pot continua prin HTTP Range, sunt validate SHA-256 și mutate atomic. Fișierele sunt identificate prin `media_id` și checksum, iar limita folosește LRU și păstrează cu prioritate playlistul activ. Dacă un material nu încape, este redat prin streaming. Ultimul playlist valid rămâne disponibil offline.

## Administrare ascunsă

Apasă rapid de cinci ori colțul stânga-sus al playerului și introdu PIN-ul. Ecranul administrativ arată dispozitivul, serverul, numai ultimele patru caractere ale cheii, playlistul și versiunea, sincronizarea, heartbeatul, internetul, cache-ul, Lock Task și ultima eroare. De aici se poate sincroniza, curăța cache-ul neutilizat, modifica setările, activa Lock Task autorizat sau ieși temporar din kiosk.

## Instalare și actualizare prin ADB

Activează Developer options și USB debugging pe tabletă, conecteaz-o și rulează:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pentru o instalare nouă se poate folosi și `adb install`. Pentru actualizare se folosește `adb install -r`, cu același `applicationId` și aceeași semnătură; astfel configurația și cache-ul rămân. APK-ul debug este semnat numai cu cheia debug locală, nu cu o cheie de producție.

## Lock Task Mode opțional

Buildul nu pornește automat screen pinning sau Lock Task. Pe o tabletă dedicată, neprovizionată, aplicația poate fi făcută device owner:

```bash
adb shell dpm set-device-owner ro.dmxconstruction.mediakiosk/.kiosk.KioskDeviceAdminReceiver
```

Operația funcționează de regulă numai înainte de adăugarea conturilor și finalizarea provizionării; poate fi necesară resetarea la setările din fabrică. După autorizare, Lock Task se activează explicit din ecranul administrativ. Ieșirea controlată se face tot de acolo.

## Pornire după boot și opțiune Home/Launcher

`BootReceiver` ascultă `BOOT_COMPLETED` și încearcă să deschidă playerul dacă aplicația este configurată. Android 10+ și unele firmware-uri de producător pot restricționa pornirea activităților din background; pentru dispozitive dedicate, soluția robustă este device owner sau folosirea aplicației ca Home.

Aliasul Home este inclus, dar dezactivat implicit. Se activează numai la cererea administratorului:

```bash
adb shell pm enable ro.dmxconstruction.mediakiosk/.KioskHomeAlias
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

Android va permite alegerea aplicației Home. Revenirea la starea inițială:

```bash
adb shell pm disable ro.dmxconstruction.mediakiosk/.KioskHomeAlias
```

## Dependențe și compatibilitate API 22

Versiunile au fost alese după metadatele AAR din Google Maven și după `checkDebugAarMetadata`. Versiunile stabile imediat următoare pentru Activity 1.10.x, Core 1.15+ și Media3 1.5+ cer `minCompileSdk = 35`, deci nu sunt compatibile cu cerința fixă `compileSdk = 34`.

| Bibliotecă | Versiune | Motiv |
|---|---:|---|
| Android Gradle Plugin | 8.5.2 | compatibil cu Gradle 8.7, JDK 17 și compileSdk 34 |
| Kotlin | 1.9.24 | stabil, compatibil AGP 8.5 |
| Core KTX | 1.13.1 | ultima stabilă compatibilă cu compileSdk 34; suportă API 22 |
| AppCompat | 1.7.1 | metadata `minCompileSdk 34`, manifest `minSdk 21` |
| Activity KTX | 1.9.3 | 1.10.x cere compileSdk 35; suportă API 22 |
| Lifecycle Runtime KTX | 2.8.7 | manifest `minSdk 19`, compatibil compileSdk 34 |
| Media3 ExoPlayer/UI | 1.4.1 | 1.5.x cere compileSdk 35; manifestul 1.4.x are minSdk 19 |
| Retrofit / Gson converter | 2.11.0 | client API fără cerință AndroidX minSdk 23 |
| OkHttp / MockWebServer | 4.12.0 | suport Android 5.0+, folosit streaming pe disc și teste |
| Coroutines Android | 1.8.1 | compatibil Kotlin 1.9 și API 22 |
| Glide | 4.16.0 | încărcare/scalare imagini compatibilă API 22 |
| Robolectric | 4.13 | teste JVM Android; versiune stabilă publicată |

Nu se folosește `tools:overrideLibrary`, Room, Compose, WorkManager sau un TrustManager permisiv.

## Teste

Testele JVM nu contactează producția. MockWebServer verifică endpointurile, headerele, JSON-ul, ETag/304 și downloadurile. Sunt acoperite validarea/ordonarea, persistența și schimbarea atomică, checksum, `.part`, Range, hit/miss, limită/LRU, fallback offline, backoff, UUID/URL, hash/salt PIN și blocarea după PIN greșit.

Testele instrumentate se compilează cu:

```bash
./gradlew compileDebugAndroidTestKotlin
```

și se rulează pe emulator/tabletă API 22+ prin:

```bash
./gradlew connectedDebugAndroidTest
```

Ele verifică ecranul de configurare, navigarea către kiosk fără a folosi producția, starea offline, immersive flags, cele cinci apăsări ascunse și secvențele imagine–imagine, imagine–video, video–imagine și repetarea cozii.

Verificare locală din 30 iulie 2026:

- 28 teste JVM: trecute;
- 10 teste instrumentate pe emulator Android API 37: trecute;
- `lintDebug`: trecut, fără erori;
- `assembleDebug`: trecut, APK debug generat;
- manifest/APK: `minSdk 22`, `targetSdk 34`, `compileSdk 34`.

## Limitări Android 5.1

- redarea H.264/AAC depinde de codec-urile hardware/software disponibile pe modelul tabletei;
- certificatele HTTPS trebuie să aibă un lanț acceptat de trust store-ul vechi al dispozitivului;
- immersive mode pe API 22 folosește flagurile system UI clasice și poate fi influențat de firmware-ul producătorului;
- pornirea automată și politicile de economisire a bateriei diferă între producători;
- setarea device owner poate necesita resetare din fabrică;
- dacă playlistul depășește cache-ul sau spațiul liber plus rezerva de 100 MB, elementele rămase necesită internet pentru streaming.
