# Changelog

## 2.0.0

**Riscrittura completa, API nuova (breaking).** Il motore 1.x consegnava gli
eventi a Dart attraverso `JobIntentService`: sotto Doze la coda JobScheduler
tratteneva le consegne per decine di minuti (misurati 22 e 95 minuti sul
campo). Il tentativo di dispatch diretto sul motore 1.x (1.3.4, mai
pubblicato) si impantanava se l'isolate di background non partiva. La 2.0 è
un progetto nuovo, validato sul campo, che tiene il dispatch diretto e lo
rende robusto:

- Dispatch diretto nel BroadcastReceiver sotto `goAsync()`: niente coda
  JobScheduler, receiver→callback Dart in decine di millisecondi.
- **Watchdog con recupero** sull'isolate di background: init assente entro
  15 s → engine distrutto e ricreato (retry schedulato, tetto 3 fallimenti,
  coda cap 32). Mai più code impantanate in silenzio.
- Ri-registrazione automatica post-reboot (receiver dedicato) + self-heal
  idempotente a ogni `initialize()`, al più una volta per boot.
- `GeofenceRegion.initialTriggers` configurabile (none/enter/exit/both).
- `promoteToForeground()`/`demoteToBackground()` per callback lunghi
  (foreground service type location; no-op su iOS).
- Evento con `fixTimestamp` (fix che ha innescato la transizione) e
  `accuracy`.
- iOS: engine headless con lo stesso watchdog, dedup delle riconsegne
  duplicate al rilancio in background, registrazione risolta su
  `didStartMonitoringFor`.

**Migrazione dalla 1.x: vedi README, sezione "Migrazione dalla 1.x".**
In particolare: API Dart nuova (callback globale con
`GeofenceTriggerEvent`), e vanno RIMOSSE le vecchie dichiarazioni manifest
`io.flutter.plugins.geofencing.*` (con la 2.0 i componenti arrivano dal
manifest merge del plugin; lasciarle causa ClassNotFoundException a ogni
boot/update dell'app).

Storia di sviluppo pre-release (come `flutter_geofence_plugin` 0.0.1-0.1.2):
watchdog, initial trigger configurabile, foreground promotion, self-heal
once-per-boot, validazione su simulatore/emulatore e sul campo (Galaxy
Z Fold6, enter/exit reali su due aree).

---

Storia 1.x (motore precedente):


## 1.3.3

### Bug fixes (Android)

- **The initial-trigger window was far too narrow.** It assumed Play Services delivers the synthetic state-sync right after `addGeofences`. Measured on a Galaxy Z Fold6 (Android 16), device sitting still at home: **3m16s and 3m36s** between registration and delivery, with the triggering fix 5 m from the geofence centre — i.e. unambiguously synthetic, yet reported as a real crossing because the 10 s window had long expired. Widened to 30 minutes. The stamp is still consumed by the first event for that id, so only the first event after a registration can ever be tagged initial.

### Observability (Android)

- The geofence callback payload now carries a 7th element with three delivery timestamps — broadcast receiver entry, just before `enqueueWork`, and start of `onHandleWork` — surfaced to Dart as `GeofencingManager.lastEventDeliveryTimings`. This splits the previously opaque fix-to-callback latency into: Play Services delivery, the synchronous `FlutterLoader` init inside the receiver, the `JobScheduler` queue (`JobIntentService` is JobScheduler-backed on API 26+, hence subject to Doze and App Standby), and background isolate startup. Without it, multi-minute enter latencies could not be attributed. Baseline on a warm fast path: 23 ms / 1 ms / 58 ms / 3 ms.

## 1.3.2

### Bug fixes (Android)

- **The initial-trigger flag was wrong whenever Play Services was fast.** `stampRegistration` was called from the `addGeofences` success listener, which is asynchronous, while the synthetic initial transition can be delivered as soon as the geofence lands. When delivery won that race, `consumeInitialTrigger` found no stamp and returned `false`, so a state-sync event was reported as a real boundary crossing: it fed flap/jitter detection and produced a redundant state-change notification on every app update or re-registration. Measured on a Galaxy Z Fold6 (Android 16): three synthetic events delivered in 58 ms, 123 ms and 948 ms, and only the 948 ms one was tagged correctly — the ordering was the bug, not the 10 s window width. Fixed by stamping **before** calling `addGeofences` (both the first-attempt and the retry path) and by using `commit()` instead of `apply()` so the value is durable before any event can read it. Stamping early is safe: a failed registration produces no events, so the stamp simply waits for the next one.

## 1.3.1

### Bug fixes (Android)

- **Critical:** `GeofencingService`'s `JobIntentService` job id was `UUID.randomUUID().mostSignificantBits.toInt()`, regenerated every time the class loaded — i.e. on every process (re)start. `JobScheduler` persists queued `JobWorkItem`s keyed by that id independently of the app process, so a process kill/restart with work still queued under the old id left `completeWork()` unable to recognize it, throwing `IllegalArgumentException`. Seen in production on Android 16 (API 36) starting with the host app's `targetSdk` 36 bump. Fixed by deriving a stable id from the class's fully-qualified name (`"io.flutter.plugins.geofencing.GeofencingService".hashCode()`), constant across restarts.

## 1.3.0

### Feature — distinguish initial-trigger events (Android)

- When a geofence is registered with `initialTrigger`, Play Services delivers a synthetic transition immediately on (re)registration (cold start, app update, reboot) reflecting the device's current location — not a real boundary crossing. Android exposes no flag for this, so consumers could not tell a state-sync from a real crossing, which polluted flap/jitter detection and produced redundant notifications on every app update.
- The plugin now stamps each geofence's registration time (`stampRegistration`) and, on the first event within a 10s window, tags it as initial (`consumeInitialTrigger`). The verdict is surfaced to the background callback via `GeofencingManager.lastEventWasInitialTrigger`, which is set immediately before the callback is invoked — read it synchronously at the top of your callback. `false` on iOS (no synthetic initial events).
- **Backward compatible:** the geofence callback signature is unchanged (still `(List<String>, Location, GeofenceEvent)`); the flag is a side-channel, and the extra native payload element is optional (older/iOS payloads default the flag to `false`). Existing 3-arg callbacks keep working without modification.

## 1.2.1

### Reliability (iOS)

- Guard `startGeofencingService:` against a stale persisted callback handle. After an in-place app update the AOT callback handle stored at registration time can fail to resolve (`FlutterCallbackCache lookupCallbackInformation:` returns nil); the previous code relied on an `NSAssert` that is compiled out in release builds and then called `runWithEntrypoint:nil`, silently starting a broken headless engine (and potentially poisoning a later valid registration via `backgroundIsolateRun`). It now logs and returns early, so a re-registration on the next launch cleanly refreshes the handle. Host apps should force-re-register geofences on an app build change to rewrite the handle proactively.

## 1.2.0

### Telemetry

- `Location` exposes a new optional `time` field carrying the time of the OS-side trigger fix. On Android this is `GeofencingEvent.triggeringLocation.time` (the GPS/network fix that satisfied the transition); on iOS it is the wall-clock timestamp captured the moment `CLLocationManager` invoked `didEnter`/`didExitRegion`, since `CLRegion` does not carry one. Lets host apps measure OS-delivery latency separately from in-app processing time. Backwards compatible — old callers that don't read `time` see no change, and the field is null on platforms / paths that did not provide a timestamp.

## 1.1.2

### Cleanup (Android)

- Guard `setLoiteringDelay` so it is only applied when `GEOFENCE_TRANSITION_DWELL` is in the trigger bitmask. Per Play Services documentation the call is a no-op without DWELL, so the previous unconditional invocation was misleading when reading the source. Behavior is unchanged for callers that did not request DWELL.

## 1.1.1

### Bug fixes (Android)

- **Critical:** Reverted `PendingIntent` to `FLAG_MUTABLE` on Android 12+. The 1.1.0 switch to `FLAG_IMMUTABLE` caused every `addGeofences` call to fail with `CommonStatusCodes.DEVELOPER_ERROR (10)`, breaking geofence registration outright. Play Services needs to write `GEOFENCE_TRANSITION`, the triggering geofences and the triggering location into the broadcast Intent extras when an event fires (see `GeofencingEvent.fromIntent`), so the PendingIntent must remain mutable.
- Verify `ACCESS_FINE_LOCATION` and (Android 10+) `ACCESS_BACKGROUND_LOCATION` at runtime *before* calling `addGeofences`. Missing permissions now surface as `PERMISSION_DENIED_FINE_LOCATION` / `PERMISSION_DENIED_BACKGROUND_LOCATION` `PlatformException`s on the Dart side instead of the opaque `DEVELOPER_ERROR (10)` from Play Services. Also fixes a pre-existing bug where the FINE_LOCATION check called `result.error()` but fell through and called `addGeofences` anyway, double-completing the result.
- Map additional Play Services error codes in `getGeofenceErrorMessage`: `DEVELOPER_ERROR (10)`, `API_NOT_CONNECTED (17)`, `NETWORK_ERROR (7)`, `INTERNAL_ERROR (8)`, `RESOLUTION_REQUIRED (6)`, `SIGN_IN_REQUIRED (4)`. These previously came back as "Unknown geofence error".
- `API_NOT_CONNECTED`, `NETWORK_ERROR` and `INTERNAL_ERROR` are now treated as transient and trigger the existing exponential-backoff retry loop. `DEVELOPER_ERROR` is intentionally non-transient because it's almost always a configuration problem the OS won't forgive on retry.

## 1.1.0

### Reliability (iOS)

- Enabled `allowsBackgroundLocationUpdates = YES` and `pausesLocationUpdatesAutomatically = NO` on `CLLocationManager` so region events keep firing when the app is backgrounded or terminated (previously both flags were commented out).
- `GeofencingPlugin.registerGeofence` now returns a structured result (`success` / `errorCode` / `message`) instead of always yielding `YES`. Regions rejected for hitting the 20-region limit or invalid coordinates surface as a `PlatformException` on the Dart side.
- `monitoringDidFailForRegion` now forwards the failure to the main isolate via `GeofencingPlugin.monitoringFailed`, exposing it through the new diagnostic stream. Previously the failure was silent and the region was quietly unmapped.

### Reliability (Android)

- `addGeofences` failures with `GEOFENCE_NOT_AVAILABLE` are now retried with exponential backoff (2s → 4s → 8s → 30s, four attempts). A recovered registration emits `registrationRecovered`; a fully exhausted retry emits `registrationFailedFinal`.
- `removeGeofenceById` now also cancels any pending retry for that id, preventing a just-removed geofence from being re-registered by an inflight retry.
- `GeofencingService` now runs a 10s watchdog after starting the background Dart isolate: if `GeofencingService.initialized` isn't reported back in time, an `isolateInitTimeout` diagnostic is emitted so the host app can recover.
- New `GeofencingManager.resetBackgroundEngine()` tears down the background `FlutterEngine` so the next geofence event spawns a fresh one.
- `PendingIntent` now uses `FLAG_IMMUTABLE` on Android 12+ (was `FLAG_MUTABLE`). Geofence intents never need mutability, and Google recommends immutable by default. *(Note: this turned out to break geofence registration with DEVELOPER_ERROR (10) and was reverted in 1.1.1.)*

### New public API

- `GeofencingManager.diagnostics` — `Stream<GeofencingDiagnostic>` of `registered`, `registrationRecovered`, `registrationFailedFinal`, `monitoringFailed`, `isolateInitTimeout`.
- `GeofencingManager.verifyRegistrations(expected)` — returns a `GeofencingVerificationReport` with `expected`, `registered`, and `missing` IDs so apps can self-heal silent drops at cold start.
- `GeofencingManager.resetBackgroundEngine()` — Android-only recovery hook.

## Changelog for 1.0.0:
* Migrated to null safety.
* Fixed PendingIntent collision bug (multiple geofences now work correctly)
* Fixed queue wrapping bug causing malformed events
* Fixed WakeLock leak in IsolateHolderService
* Made foreground service notification configurable
* Added proper null checks in callback dispatcher (release mode safe)
* Added iOS monitoring failure handling with error logging
* Added iOS region limit check (max 20 regions)
* Added coordinate and radius validation
* Added auto-initialization enforcement
* Added GeofencingException for better error handling
* Added removeAllGeofences() and isSupported helpers
* Improved error propagation from native to Dart


## 0.1.0

* Updated to Flutter Android embedding v2.

## 0.0.1

* Initial sample plugin.
