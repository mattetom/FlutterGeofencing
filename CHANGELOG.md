## Changelog for 0.3.0:
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
