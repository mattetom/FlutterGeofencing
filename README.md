# geofencing_service

[![pub package](https://img.shields.io/pub/v/geofencing_service.svg)](https://pub.dev/packages/geofencing_service)

Flutter plugin for **circular geofences**: **enter**, **exit**, and (on Android) **dwell** events, with background execution through a dedicated callback dispatcher.

## Status
This package is **actively maintained** ([repository](https://github.com/mattetom/FlutterGeofencing), [issues](https://github.com/mattetom/FlutterGeofencing/issues)).

## Features
- Register and remove geofences with unique IDs.
- Background callbacks and robust error handling.
- Platform limits and coordinate/radius validation.
- Android-specific options (initial trigger, loitering, notification responsiveness).
- **1.1.0+**: diagnostic stream, registration verification, Android recovery, and iOS reliability fixes (see [CHANGELOG](CHANGELOG.md)).

## Platform support
- Android
- iOS

## Important notes
- iOS allows at most **20** geofences; Android **100**.
- iOS does **not** support `GeofenceEvent.dwell`.
- Use a radius of at least **100 m** for reliable detection.
- The callback must be **top-level** or **static** and annotated with `@pragma('vm:entry-point')`.

## Getting started
Add to your `pubspec.yaml`:

```yaml
dependencies:
  geofencing_service: ^1.1.0
```

Import:

```dart
import 'package:geofencing_service/geofencing_service.dart';
```

## Usage example

```dart
import 'package:geofencing_service/geofencing_service.dart';
import 'package:permission_handler/permission_handler.dart';

@pragma('vm:entry-point')
void geofenceCallback(List<String> ids, Location l, GeofenceEvent e) {
  print('Geofence: $ids, location: $l, event: $e');
}

Future<void> setupGeofencing() async {
  final whenInUse = await Permission.locationWhenInUse.request();
  final always = await Permission.locationAlways.request();
  if (!whenInUse.isGranted || !always.isGranted) return;

  await GeofencingManager.initialize();

  final region = GeofenceRegion(
    'home',
    45.675120,
    8.952792,
    200.0,
    <GeofenceEvent>[GeofenceEvent.enter, GeofenceEvent.exit],
    AndroidGeofencingSettings(
      initialTrigger: <GeofenceEvent>[GeofenceEvent.enter, GeofenceEvent.exit],
      loiteringDelay: 0,
      notificationResponsiveness: 0,
    ),
  );

  await GeofencingManager.registerGeofence(region, geofenceCallback);
}
```

## API overview
- `GeofencingManager.initialize()` — initializes the service and dispatcher.
- `GeofencingManager.registerGeofence(region, callback)` — register; errors surface as `GeofencingException` / `PlatformException`.
- `GeofencingManager.getRegisteredGeofenceIds()` — list of IDs.
- `GeofencingManager.removeGeofenceById` / `removeGeofence` / `removeAllGeofences`.
- `GeofencingManager.isSupported` — `true` on Android/iOS.
- `GeofencingManager.maxGeofences` — platform limit.
- `GeofencingManager.diagnostics` — `Stream<GeofencingDiagnostic>` (1.1.0+).
- `GeofencingManager.verifyRegistrations(expected)` — verification report (1.1.0+).
- `GeofencingManager.resetBackgroundEngine()` — Android only, recovery (1.1.0+).

## Android

### `AndroidManifest.xml`
```xml
<receiver
    android:name="io.flutter.plugins.geofencing.GeofencingBroadcastReceiver"
    android:enabled="true"
    android:exported="true" />

<service
    android:name="io.flutter.plugins.geofencing.GeofencingService"
    android:permission="android.permission.BIND_JOB_SERVICE"
    android:exported="true" />

<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

### `Application` class
Create `Application.kt` or `Application.java` next to `MainActivity`:

```kotlin
class Application : FlutterApplication(), PluginRegistrantCallback {
  override fun onCreate() {
    super.onCreate()
    GeofencingService.setPluginRegistrant(this)
  }

  override fun registerWith(registry: PluginRegistry) {
  }
}
```

Or in Java:

```java
public class Application extends FlutterApplication implements PluginRegistrantCallback {
  @Override
  public void onCreate() {
    super.onCreate();
    GeofencingService.setPluginRegistrant(this);
  }

  @Override
  public void registerWith(PluginRegistry registry) {
  }
}
```

Reference it in the manifest:

```xml
<application
    android:name=".Application"
    ...
```

## iOS

### `Info.plist`
```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>YOUR DESCRIPTION HERE</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>YOUR DESCRIPTION HERE</string>
```

Enable background location:

```xml
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
</array>
```

### Header / bridging
In `Runner-Bridging-Header.h` (Swift) or wherever you import the plugin header:

```objc
#import <geofencing_service/GeofencingPlugin.h>
```

### `AppDelegate` (Swift)
```swift
GeofencingPlugin.setPluginRegistrantCallback { (registry) in
  GeneratedPluginRegistrant.register(with: registry)
}
```

## Permissions (with `permission_handler`)
In the app’s `Podfile`, if you use `permission_handler`’s iOS permission macros:

```ruby
post_install do |installer|
  installer.pods_project.targets.each do |target|
    flutter_additional_ios_build_settings(target)
    target.build_configurations.each do |config|
      config.build_settings['ENABLE_BITCODE'] = 'NO'
      config.build_settings['GCC_PREPROCESSOR_DEFINITIONS'] ||= [
        '$(inherited)',
        'PERMISSION_LOCATION=1',
      ]
    end
  end
end
```

## Example app
The [`example/`](example/) directory contains a sample with UI, permissions, and local notifications. After changing dependencies, run `pod install` in the example’s `ios` folder on iOS if needed.

## License
See [LICENSE](LICENSE) (BSD-style license derived from the Chromium project).

## References
- [Geofencing on Android (official documentation)](https://developer.android.com/training/location/geofencing)
- [Publishing to pub.dev](PUBLISHING.md) (for maintainers)
