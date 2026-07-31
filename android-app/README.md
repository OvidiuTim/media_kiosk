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

## HTTPS pe Android 5.1

Android 5.1 de pe unele dispozitive rk3288 nu include în trust store rădăcina necesară lanțului actual Let’s Encrypt. Aplicația include certificatul CA autosemnat **ISRG Root X1**, nu certificatul leaf temporar al domeniului:

- sursă oficială: [Let’s Encrypt — `isrgrootx1.pem`](https://letsencrypt.org/certs/isrgrootx1.pem);
- subject și issuer: `C=US, O=Internet Security Research Group, CN=ISRG Root X1`;
- valabilitate: 4 iunie 2015 – 4 iunie 2035;
- fingerprint certificat SHA-256: `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6`.

Fingerprint-ul este verificat din nou la runtime înainte de construirea trust managerului. Pentru `kiosk.dmxconstruction.ro`, clientul încearcă mai întâi validarea standard a sistemului și folosește trust store-ul limitat la ISRG Root X1 numai dacă sistemul nu poate construi lanțul. Pentru orice alt hostname se folosește exclusiv clientul standard. Verificarea hostname-ului este implementarea implicită OkHttp; redirecturile fallback către alt host sunt respinse și conexiunile HTTP sunt interzise.

Pe API 22 clientul permite TLS 1.2. Pe Android modern rămâne configurația TLS implicită modernă. Același `Call.Factory` securizat este folosit de Retrofit pentru test/API/sincronizare/heartbeat, de cache-ul media, de Media3 prin `OkHttpDataSource` și de Glide prin `OkHttpUrlLoader`. Astfel imaginile și videoclipurile redate prin streaming nu deschid un flux HTTPS separat cu alt trust manager.

În buildul debug, o eroare TLS afișează și stack trace-ul complet după mesajul pentru utilizator. Buildul nu conține trust-all, `HostnameVerifier` personalizat/permisiv sau tratarea `SSLHandshakeException` ca succes.

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

## Diagnosticarea închiderilor neașteptate

`MediaKioskApplication` instalează un `UncaughtExceptionHandler` încă din `attachBaseContext()`, înainte de `Application.onCreate()` și de fluxul normal al activităților. La o excepție necapturată, handlerul construiește și scrie sincron stack trace-ul complet în `filesDir/crash_reports/last_crash.txt`, sincronizează fișierul pe disc și abia apoi pasează excepția handlerului Android care închide procesul. Raportul conține data, threadul, versiunea Android, modelul, hardware-ul, ABI-ul și întregul lanț de excepții; nu conține cheia dispozitivului sau PIN-ul.

La următoarea pornire, indiferent dacă intrarea este configurarea, playerul sau administrarea, aplicația afișează înaintea fluxului normal pagina **Aplicația s-a închis neașteptat**. Raportul poate fi copiat sau partajat ca text și ca fișier prin `FileProvider`, fără permisiune de stocare. **Încearcă din nou** șterge raportul și revine la fluxul obișnuit; dacă aplicația este configurată, configurarea este sărită și se deschide playerul.

Pagina de diagnostic folosește numai `android.app.Activity` și componente platformă disponibile în API 22. Un marker intern împiedică handlerul să suprascrie raportul original dacă pagina de diagnostic se închide ea însăși cu eroare; următoarea pornire ocolește pagina o singură dată, evitând o buclă de crash. Handlerul Java/Kotlin nu poate raporta terminări native (`SIGSEGV`), ANR, opriri din lipsă de memorie sau întreruperea alimentării.

## Administrare ascunsă

Apasă rapid de cinci ori colțul stânga-sus al playerului și introdu PIN-ul. Ecranul administrativ arată dispozitivul, serverul, numai ultimele patru caractere ale cheii, playlistul și versiunea, sincronizarea, heartbeatul, internetul, cache-ul, starea ultimei porniri, Home și Lock Task. De aici se poate sincroniza, curăța cache-ul, modifica setările, controla pornirea automată, deschide selectorul Home, activa explicit un Lock Task deja autorizat sau ieși temporar din kiosk.

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

Comutatorul **Pornire automată după restart** este activ implicit în ecranul administrativ. Receiverul pornește exclusiv `KioskActivity`, numai dacă aplicația este deja configurată și comutatorul este activ. Nu deschide configurarea după boot. Playerul citește mai întâi playlistul și cache-ul privat, deci pornește offline când există o versiune locală validă.

Manifestul declară `RECEIVE_BOOT_COMPLETED`, iar receiverul exportat ascultă `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` și `USER_UNLOCKED`. Pe Android 7+ receiverul este Direct Boot aware, dar cheia, PIN-ul, playlistul și media rămân intenționat în credential-protected storage. La `LOCKED_BOOT_COMPLETED` se salvează numai un indicator nesensibil în device-protected storage; playerul este pornit după deblocare, când cache-ul poate fi citit. Broadcasturile apropiate sunt deduplicate.

Comportamentul depinde de versiunea Android:

- Android 5.1–9: receiverul poate porni direct activitatea după `BOOT_COMPLETED`;
- Android 10–14: pornirea activităților din background poate fi blocată. Pentru o tabletă dedicată, metoda robustă este Media Kiosk ca aplicație Home; Android pornește automat Home după boot;
- un device owner configurat separat poate porni direct, dar aplicația nu activează automat Device Owner și nu activează automat Lock Task;
- pe Android 13+ categoria de baterie **Restricted/Restricționată** poate amâna inclusiv broadcasturile de boot până când aplicația este pornită manual.

Documentație Android: [restricțiile de background activity launch](https://developer.android.com/guide/components/activities/background-starts), [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot) și [custom Home pentru dispozitive dedicate](https://developer.android.com/work/dpc/dedicated-devices/cookbook#be-home-app).

### Selectarea Media Kiosk ca Home

1. Intră în administrare prin cinci apăsări și PIN.
2. Apasă **Setează Media Kiosk ca aplicație principală**.
3. Confirmă **Deschide selectorul**.
4. În selectorul Android alege **Media Kiosk**, apoi **Întotdeauna**.

Pe Android 10+ se folosește cererea oficială pentru rolul `ROLE_HOME`; pe versiunile mai vechi se deschide pagina sistemului pentru aplicația Home. Aliasul cu `MAIN + HOME + DEFAULT` este dezactivat implicit și devine candidat numai după acțiunea explicită a administratorului.

Pentru revenire:

1. Intră în administrare cu PIN-ul.
2. Apasă **Revino la launcherul sistemului**.
3. Confirmă din nou PIN-ul. După trei încercări greșite se aplică blocarea de 30 de secunde.
4. În setările Home deschise de aplicație alege launcherul sistemului și **Întotdeauna**.

Aliasul Media Kiosk este dezactivat numai după PIN corect. Nu există o acțiune neprotejată în player care să schimbe aplicația Home.

### Android 5.1 — pași recomandați

1. Instalează APK-ul, pornește-l o dată și finalizează configurarea.
2. În administrare lasă activ **Pornire automată după restart**.
3. Pentru comportament kiosk robust, apasă **Setează Media Kiosk ca aplicație principală**; pe AOSP 5.1 selectorul poate apărea ca **Settings → Home** sau **Settings → Apps → Default apps → Home**.
4. Alege **Media Kiosk → Întotdeauna** și repornește tableta.
5. Dacă nu folosești Home, receiverul pornește direct playerul după boot, dar firmware-ul producătorului poate cere permisiune separată de autostart.

### Android 14 — pași recomandați

1. Finalizează configurarea și activează comutatorul de autostart.
2. Apasă butonul Home din administrare și acceptă rolul **Home app** pentru Media Kiosk.
3. Verifică **Settings → Apps → Default apps → Home app → Media Kiosk**.
4. Deschide **Settings → Apps → Media Kiosk → App battery usage** și selectează **Unrestricted** sau activează **Allow background usage**, dacă opțiunea există.
5. Nu seta aplicația în categoria **Restricted**; aceasta poate împiedica livrarea `BOOT_COMPLETED` pentru aplicațiile care țintesc API 33+.
6. Repornește tableta și verifică redarea cu Wi-Fi oprit pentru fallbackul offline.

### Setări speciale ale producătorilor

Denumirile pot varia între versiuni, dar verifică explicit ambele permisiuni: autostart și baterie fără restricții.

- Samsung One UI: **Settings → Apps → Media Kiosk → Battery → Unrestricted**; apoi **Battery and device care → Battery → Background usage limits → Never auto sleeping apps** și adaugă Media Kiosk.
- Xiaomi MIUI/HyperOS: **Settings → Apps → Permissions → Background autostart → Media Kiosk**; apoi **Battery → Media Kiosk → No restrictions**.
- Huawei EMUI: **Settings → Battery → App launch → Media Kiosk → Manage manually** și activează **Auto-launch**, **Secondary launch**, **Run in background**.
- OPPO/Realme: **Settings → Apps → Auto launch → Media Kiosk**; apoi **Battery usage → Allow background activity/No restrictions**.
- OnePlus/OxygenOS: **Settings → Apps → Auto-launch → Media Kiosk**; apoi **Battery → Battery optimization → Don’t optimize**.
- Lenovo/Motorola: **Settings → Apps → Special app access → Battery optimization → All apps → Media Kiosk → Don’t optimize**; verifică și opțiunea vendor **Auto-start** dacă există.

Pe firmware-uri kiosk administrate, Home implicit este preferabil excepțiilor OEM de baterie. Aceste setări nu activează Device Owner sau Lock Task.

Administrare alternativă prin ADB a aliasului Home:

```bash
adb shell pm enable ro.dmxconstruction.mediakiosk/.KioskHomeAlias
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

Revenirea la starea inițială:

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
| Media3 ExoPlayer/UI/OkHttp datasource | 1.4.1 | 1.5.x cere compileSdk 35; manifestul 1.4.x are minSdk 19 |
| Retrofit / Gson converter | 2.11.0 | client API fără cerință AndroidX minSdk 23 |
| OkHttp / MockWebServer | 4.12.0 | suport Android 5.0+, folosit streaming pe disc și teste |
| Coroutines Android | 1.8.1 | compatibil Kotlin 1.9 și API 22 |
| Glide + OkHttp integration | 4.16.0 | încărcare/scalare imagini prin clientul HTTPS comun, compatibilă API 22 |
| Robolectric | 4.13 | teste JVM Android; versiune stabilă publicată |

Nu se folosește `tools:overrideLibrary`, Room, Compose, WorkManager sau un TrustManager permisiv.

Auditul startup-ului pentru Android 5.1 include:

- temele și toate drawable-urile folosesc resurse disponibile în API 22; tema de diagnostic este `android:style/Theme.Material.NoActionBar`, disponibilă din API 21;
- proiectul nu importă `java.time`; data raportului folosește `SimpleDateFormat`;
- compilarea Java/Kotlin 17 trece prin desugaringul D8 al Android Gradle Plugin. Nu este necesar `coreLibraryDesugaring`, deoarece sursele nu folosesc API-uri de bibliotecă Java absente din API 22;
- `networkSecurityConfig` este folosit în API 24+, iar `usesCleartextTraffic` în API 23+. Android 5.1 le ignoră în mod compatibil; pe API 22, `ServerUrl` și validarea playlistului acceptă numai URL-uri HTTPS, iar traficul folosește OkHttp;
- `lockTaskMode` din manifest este ignorat pe API 22, iar codul folosește fallbackul `ActivityManager.isInLockTaskMode` disponibil în această versiune;
- manifestele AAR verificate declară `minSdk 19` pentru Media3 ExoPlayer/UI 1.4.1 și Core KTX 1.13.1, respectiv `minSdk 21` pentru AppCompat 1.7.1;
- un test Robolectric configurat explicit cu SDK 22 construiește `SetupActivity`, pagina de diagnostic și un `KioskActivity` configurat, inclusiv instanțierea Media3 `ExoPlayer`.

### Remediere pentru firmware Rockchip Android 5.1.1

Pe tableta rk3288, primul `<Button>` din `activity_setup.xml`, `testButton` cu textul **Testează conexiunea**, se închidea în constructor înainte de afișarea ecranului. În sursa AOSP Android 5.1.1, linia raportată `TextView.java:1015` este citirea `fontFamily = a.getString(attr)`. Aplicația furniza `android:fontFamily="sans"` în `Theme.MediaKiosk`, iar tagul `Button` era substituit de AppCompat cu `AppCompatButton` și primea lanțul implicit `buttonStyle → Widget.AppCompat.Button → android:textAppearance`. Firmware-ul Rockchip interpreta greșit indexul stringului din `TypedArray` și încerca indexul 667 într-un `StringBlock` cu 41 intrări.

Tema nu mai declară `android:fontFamily`. Acțiunile din configurare și administrare sunt `TextView` simple, clickable și focusable, cu background selector local compatibil API 22; nu au `style`, `textAppearance`, `fontFamily` sau `textAllCaps`. Și dialogurile aplicației folosesc acțiuni `TextView` programatice, nu butoanele interne ale `AlertDialog`. Testele API 22 inspectează XML-ul binar compilat, interzic tagul `Button` și atributele text problematice pe toate acțiunile, apoi inflează efectiv configurarea, administrarea și dialogul sigur.

## Teste

Testele JVM nu contactează producția. MockWebServer verifică endpointurile, headerele, JSON-ul, ETag/304 și downloadurile. Sunt acoperite validarea/ordonarea, persistența și schimbarea atomică, checksum, `.part`, Range, hit/miss, limită/LRU, fallback offline, backoff, UUID/URL, hash/salt PIN, blocarea după PIN greșit, boot configurat/neconfigurat, autostart activ/inactiv, Direct Boot, pornire offline, politica Android modern, selectorul Home și ieșirea protejată.

Testele instrumentate se compilează cu:

```bash
./gradlew compileDebugAndroidTestKotlin
```

și se rulează pe emulator/tabletă API 22+ prin:

```bash
./gradlew connectedDebugAndroidTest
```

Ele verifică ecranul de configurare, navigarea către kiosk fără a folosi producția, starea offline, immersive flags, cele cinci apăsări ascunse și secvențele imagine–imagine, imagine–video, video–imagine și repetarea cozii.

Verificare locală din 31 iulie 2026:

- 57 teste JVM: trecute; 19 teste rulează explicit cu profil API 22, inclusiv TLS, certificatul CA, inflation și auditul acțiunilor/dialogurilor;
- 14 teste instrumentate pe emulator Android API 37: trecute;
- `lintDebug`: trecut, fără erori și fără probleme `NewApi`;
- `assembleDebug`: trecut, APK debug generat;
- manifest/APK: `minSdk 22`, `targetSdk 34`, `compileSdk 34`.

## Limitări Android 5.1

- redarea H.264/AAC depinde de codec-urile hardware/software disponibile pe modelul tabletei;
- certificatele HTTPS trebuie să aibă un lanț acceptat de trust store-ul vechi al dispozitivului;
- immersive mode pe API 22 folosește flagurile system UI clasice și poate fi influențat de firmware-ul producătorului;
- pornirea automată și politicile de economisire a bateriei diferă între producători;
- setarea device owner poate necesita resetare din fabrică;
- dacă playlistul depășește cache-ul sau spațiul liber plus rezerva de 100 MB, elementele rămase necesită internet pentru streaming.
