# Changelog

## 1.1.0

### Reliability (iOS)

- Enabled `allowsBackgroundLocationUpdates = YES` and `pausesLocationUpdatesAutomatically = NO` on `CLLocationManager` so region events keep firing when the app is backgrounded or terminated (previously both flags were commented out).
- `GeofencingPlugin.registerGeofence` now returns a structured result (`success` / `errorCode` / `message`) instead of always yielding `YES`. Regions rejected for hitting the 20-region limit or invalid coordinates surface as a `PlatformException` on the Dart side.
- `monitoringDidFailForRegion` now forwards the failure to the main isolate via `GeofencingPlugin.monitoringFailed`, exposing it through the new diagnostic stream. Previously the failure was silent and the region was quietly unmapped.

### Reliability (Android)

- `addGeofences` failures with `GEOFENCE_NOT_AVAILABLE` are now retried with exponential backoff (2s → 4s → 8s → 30s, four attempts). A recovered registration emits `registrationRecovered`; a fully exhausted retry emits `registrationFailedFinal`.
- Runtime permission check before `addGeofences`: `ACCESS_FINE_LOCATION` (and `ACCESS_BACKGROUND_LOCATION` on Android 10+) are now verified up-front and surface as `PERMISSION_DENIED_FINE_LOCATION` / `PERMISSION_DENIED_BACKGROUND_LOCATION` `PlatformException`s. Previously a missing background permission was rejected by Play Services as the opaque `DEVELOPER_ERROR` (code 10) and the original FINE_LOCATION check fell through and called `addGeofences` anyway, causing a double `result` callback.
- Error message mapping now covers `CommonStatusCodes.DEVELOPER_ERROR` (10), `API_NOT_CONNECTED` (17), `NETWORK_ERROR` (7), `INTERNAL_ERROR` (8), `RESOLUTION_REQUIRED` (6), `SIGN_IN_REQUIRED` (4) — previously these came back as "Unknown geofence error". `API_NOT_CONNECTED`, `NETWORK_ERROR`, and `INTERNAL_ERROR` are now treated as transient and trigger the retry loop; `DEVELOPER_ERROR` is intentionally non-transient because it's almost always a configuration problem.
- `removeGeofenceById` now also cancels any pending retry for that id, preventing a just-removed geofence from being re-registered by an inflight retry.
- `GeofencingService` now runs a 10s watchdog after starting the background Dart isolate: if `GeofencingService.initialized` isn't reported back in time, an `isolateInitTimeout` diagnostic is emitted so the host app can recover.
- New `GeofencingManager.resetBackgroundEngine()` tears down the background `FlutterEngine` so the next geofence event spawns a fresh one.
- Confirmed `PendingIntent.FLAG_MUTABLE` on Android 12+: Play Services needs to write the transition data into the broadcast Intent extras, so the PendingIntent must remain mutable. Switching to `FLAG_IMMUTABLE` was attempted briefly but reverted because it makes `addGeofences` fail with `DEVELOPER_ERROR (10)`.

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
