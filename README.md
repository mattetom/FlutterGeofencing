# geofencing_service

Plugin Flutter di geofencing **nativo** per Android e iOS: registri una o più
aree circolari e ricevi un **callback Dart in background** a ogni ingresso o
uscita, **anche quando l'app è terminata**.

- **Android**: `GeofencingClient` di Play Services + `BroadcastReceiver` con
  dispatch diretto verso un engine Flutter headless (niente JobScheduler:
  sotto Doze accoderebbe la consegna per minuti).
- **iOS**: region monitoring di Core Location; se l'app è stata terminata,
  iOS la rilancia in background all'attraversamento e il plugin esegue il
  callback in un engine headless dedicato.

L'app di esempio (`example/`) mostra il caso d'uso classico: notifica locale
"sei entrato / sei uscito" via `flutter_local_notifications`.

**v2.0**: riscrittura completa del motore (la 1.x consegnava via
JobIntentService: sotto Doze la coda tratteneva gli eventi per decine di
minuti). Dispatch diretto con watchdog di recupero sull'isolate, validato
end-to-end su simulatore/emulatore e sul campo. API nuova: vedi la sezione
**Migrazione dalla 1.x** in fondo.

## Uso

```dart
import 'package:geofencing_service/flutter_geofence_plugin.dart';

// Callback top-level (o static), OBBLIGATORIAMENTE annotato vm:entry-point:
// gira in un isolate dedicato, anche ad app terminata, quindi non può
// leggere lo stato dell'app.
@pragma('vm:entry-point')
Future<void> onGeofence(GeofenceTriggerEvent event) async {
  // event.regionIds, event.type (enter/exit), event.latitude/longitude
}

await FlutterGeofencePlugin.initialize(onGeofence);      // a ogni avvio
await FlutterGeofencePlugin.requestPermissions();        // "Consenti sempre"

await FlutterGeofencePlugin.registerGeofence(GeofenceRegion(
  id: 'casa',
  latitude: 45.4642,
  longitude: 9.1900,
  radiusMeters: 200,
  triggers: {GeofenceEventType.enter, GeofenceEventType.exit},
));

await FlutterGeofencePlugin.getRegisteredGeofenceIds();  // ['casa']
await FlutterGeofencePlugin.removeGeofence('casa');
```

Se il dispositivo è **già dentro** l'area al momento della registrazione,
riceve subito un evento `enter` (parità Android/iOS). Il comportamento è
configurabile per regione con `initialTriggers` (default `{enter}`):

```dart
GeofenceRegion(
  id: 'casa', latitude: ..., longitude: ..., radiusMeters: 200,
  // Evento sintetico alla registrazione che riflette lo stato corrente:
  // utile come recovery dello stato alla ri-registrazione. {} = nessuno.
  initialTriggers: {GeofenceEventType.enter, GeofenceEventType.exit},
)
```

L'evento porta anche `fixTimestamp` (quando l'OS ha prodotto il fix che ha
innescato la transizione — la differenza con `timestamp` misura il ritardo
di consegna) e `accuracy` (accuratezza orizzontale del fix in metri).

Per callback lunghi (es. chiamate di rete) su Android:

```dart
@pragma('vm:entry-point')
Future<void> onGeofence(GeofenceTriggerEvent event) async {
  await FlutterGeofencePlugin.promoteToForeground(); // foreground service
  try {
    // ... lavoro lungo: l'OS non uccide il processo ...
  } finally {
    await FlutterGeofencePlugin.demoteToBackground();
  }
}
```

Chiamabili solo dall'interno del callback; no-op su iOS.

## Setup Android

I permessi (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`,
`RECEIVE_BOOT_COMPLETED`) e i receiver arrivano già dal manifest del plugin:
non serve toccare il manifest dell'app per il geofencing.

- La richiesta runtime la fa `requestPermissions()`: prima il permesso
  foreground, poi quello background (su Android 11+ il sistema porta
  l'utente nelle impostazioni per "Consenti sempre" — se torna `false`,
  richiamarla o guidare l'utente nelle impostazioni).
- Dopo il **reboot** le geofence vengono ri-registrate automaticamente dal
  `RebootBroadcastReceiver` del plugin (Android le cancella al riavvio).
- Nel callback si possono usare altri plugin (es. notifiche): l'engine
  headless registra da solo i plugin dell'app.
- Se l'app usa `flutter_local_notifications`, servono all'app (non al
  plugin) il permesso `POST_NOTIFICATIONS` nel manifest e il core library
  desugaring: vedi `example/android/app/`.

## Setup iOS

1. In `Info.plist` dell'app:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Perché serve la posizione…</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Perché serve la posizione in background…</string>
```

2. In `AppDelegate.swift`, registra i plugin dell'app sull'engine headless
   (serve solo se il callback usa altri plugin, es. le notifiche):

```swift
import flutter_geofence_plugin

// dentro didFinishLaunchingWithOptions, prima del return:
FlutterGeofencePlugin.setPluginRegistrantCallback { registry in
  GeneratedPluginRegistrant.register(with: registry)
}
```

Non serve la background mode "Location updates": il region monitoring e il
rilancio dell'app terminata sono gestiti dal sistema.

## Limiti e note

- **iOS: massimo 20 regioni** monitorate per app (limite di sistema;
  `registerGeofence` fallisce con `region_limit` oltre la soglia).
  Su Android il limite è 100.
- **Raggio minimo consigliato ~100-150 m**: sotto, l'affidabilità cala su
  entrambe le piattaforme (su iOS il rilevamento può richiedere fino a
  qualche minuto; Android sotto Doze può ritardare gli exit).
- Il callback ha **~10 secondi** di budget garantito su Android (finestra
  del receiver): per lavori lunghi meglio schedulare un task e uscire.
- Gli id delle aree sono univoci: ri-registrare lo stesso id sostituisce
  la regione precedente.
- Su iOS le regioni monitorate **persistono** tra i riavvii dell'app e del
  telefono (le gestisce il sistema); su Android la persistenza è emulata
  dal plugin (SharedPreferences + receiver di reboot).

## Robustezza del canale di consegna

Lezione ereditata dal plugin storico di GeoCam (il cui dispatch diretto si
impantanò sul campo): un isolate di background che non si inizializza NON
deve bloccare la coda eventi in silenzio. Qui un **watchdog** distrugge e
ricrea l'engine se l'init non arriva entro 15 s (retry schedulato + al
prossimo evento, tetto di 3 fallimenti consecutivi, coda limitata a 32);
tutto osservabile in logcat con `adb logcat -s GeofenceBgRunner
GeofenceReceiver`.


## Migrazione dalla 1.x

La 2.0 è una riscrittura: l'API è nuova e più piccola. Mappa dei concetti:

| 1.x | 2.0 |
|---|---|
| `GeofencingManager.initialize()` | `FlutterGeofencePlugin.initialize(callback)` — il callback è **globale**, uno per app |
| `registerGeofence(region, callback)` | `registerGeofence(region)` (il callback sta in `initialize`) |
| `GeofenceRegion(id, lat, lng, radius, triggers, androidSettings)` (posizionale) | `GeofenceRegion(id:, latitude:, longitude:, radiusMeters:, triggers:, initialTriggers:)` (named) |
| `AndroidGeofencingSettings.initialTrigger` | `GeofenceRegion.initialTriggers` (cross-platform: su iOS è emulato con `requestState`) |
| callback `(List<String> ids, Location l, GeofenceEvent e)` | callback `(GeofenceTriggerEvent event)` — dentro: `regionIds`, `type`, `latitude`, `longitude`, `fixTimestamp` (ex `Location.time`), `accuracy` |
| `GeofenceEvent.dwell` | rimosso (mai supportato su iOS) |
| `promoteToForeground()` / `demoteToBackground()` | invariati (`FlutterGeofencePlugin.*`), ora su foreground service type `location` |
| `getRegisteredGeofenceIds()` / `removeGeofenceById(id)` | `getRegisteredGeofenceIds()` / `removeGeofence(id)` |
| `GeofencingManager.diagnostics` (stream) | rimosso: il plugin **si auto-ripara** (watchdog + self-heal); osservabilità via logcat (`GeofenceBgRunner`, `GeofenceReceiver`) |
| `verifyRegistrations(expected)` | rimosso: confronta `expected` con `getRegisteredGeofenceIds()` (2 righe) — e il self-heal ri-registra comunque a ogni avvio |
| `resetBackgroundEngine()` | rimosso: lo fa il watchdog da solo |
| `lastEventWasInitialTrigger` / `lastEventDeliveryTimings` | rimossi: usare `fixTimestamp`/`timestamp` dell'evento; l'euristica initial si implementa app-side se serve (timbro alla registrazione, finestra 30 min) |

Se vuoi migrare senza toccare i call site, puoi fare da subito un piccolo
**shim** che espone i nomi 1.x sopra l'API 2.0 (pattern usato in produzione
da GeoCam: `lib/geofence_compat.dart` nel suo repo).

### ⚠️ Manifest Android: rimuovi le dichiarazioni 1.x

Con la 2.0 receiver e service arrivano dal **manifest merge del plugin**:
l'app NON deve dichiarare nulla. Se il tuo manifest ha le vecchie
dichiarazioni della 1.x, VANNO RIMOSSE:

```xml
<!-- da eliminare: le classi non esistono più nella 2.0 -->
<receiver android:name="io.flutter.plugins.geofencing.GeofencingBroadcastReceiver" .../>
<receiver android:name="io.flutter.plugins.geofencing.GeofencingRebootBroadcastReceiver" .../>
<service android:name="io.flutter.plugins.geofencing.GeofencingService" .../>
<service android:name="io.flutter.plugins.geofencing.IsolateHolderService" .../>
```

Lasciarle non è innocuo: al primo `BOOT_COMPLETED` o `MY_PACKAGE_REPLACED`
l'app **crasha** (`ClassNotFoundException`) e la ri-registrazione post-reboot
salta — l'app resta sorda finché non viene riaperta (caso reale, 21/08/2026).
Su Android/One UI un crash-loop al boot può inoltre far scattare la
restrizione batteria automatica ("Batteria → Con limitazioni"), che scarta
TUTTI i broadcast verso l'app in silenzio: dopo la migrazione verifica lo
stato con `ActivityManager.isBackgroundRestricted()`.

### iOS

`setPluginRegistrantCallback` esiste identico; cambia solo l'import
(`import geofencing_service`) e la classe è sempre `FlutterGeofencePlugin`.
Le regioni monitorate (`CLLocationManager.monitoredRegions`) sopravvivono
all'update: alla prima `initialize()` gli handle del callback vengono
rinfrescati e tutto riprende senza ri-registrare.

## Provare l'example

```bash
cd example
flutter run
```

1. Concedi notifiche e posizione **"Consenti sempre"** quando richiesto.
2. Registra un'area attorno a una posizione che puoi simulare.
3. **Termina l'app** (swipe dal task switcher).
4. Simula lo spostamento:
   - **iOS Simulator**: `xcrun simctl location booted set <lat>,<lng>`
     (prima fuori dall'area, poi dentro), oppure Features → Location.
   - **Emulatore Android**: Extended controls (⋯) → Location → imposta un
     punto fuori e poi dentro l'area. Serve un'immagine con Play Services.
   - **Dispositivo reale**: attraversa fisicamente il confine dell'area.
5. Arriva la notifica di ingresso/uscita senza riaprire l'app.

Su Android il primo evento ad app terminata può richiedere qualche decina
di secondi (avvio processo + engine headless); i successivi sono immediati
finché il processo resta vivo.
