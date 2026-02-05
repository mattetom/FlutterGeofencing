# Geofencing

Flutter plugin to register circular geofences and receive **enter**, **exit** (and **dwell** on Android) events, including background execution via a dedicated callback dispatcher.

## Status
This package is **actively maintained**.

## Features
- Register/remove geofences with unique IDs.
- Background callback execution with robust error handling.
- Platform limits and validation handled safely.
- Android‑specific settings (initial trigger, loitering, responsiveness).

## Platform support
- Android ✅
- iOS ✅

## Important notes
- iOS supports a maximum of **20** geofences; Android **100**.
- iOS **does not support** `GeofenceEvent.dwell`.
- Recommended minimum radius: **100m** for reliable detection.
- The callback must be **top‑level** or **static** and annotated with `@pragma('vm:entry-point')`.

## Getting started
Add the dependency to your `pubspec.yaml`:

```yaml
dependencies:
  geofencing: ^0.3.1
```

## Usage

```dart
import 'package:geofencing_service/geofencing_service.dart';
import 'package:permission_handler/permission_handler.dart';

@pragma('vm:entry-point')
void geofenceCallback(List<String> ids, Location l, GeofenceEvent e) {
  // Handle events here (log, notification, state update, etc.)
  print('Geofence: $ids, location: $l, event: $e');
}

Future<void> setupGeofencing() async {
  // 1) Request permissions
  final whenInUse = await Permission.locationWhenInUse.request();
  final always = await Permission.locationAlways.request();
  if (!whenInUse.isGranted || !always.isGranted) return;

  // 2) Initialize the plugin
  await GeofencingManager.initialize();

  // 3) Register a geofence
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
- `GeofencingManager.initialize()`
  Initializes the service and the callback dispatcher.

- `GeofencingManager.registerGeofence(region, callback)`
  Registers a geofence. Validates coordinates, platform limits, and returns errors as `GeofencingException`.

- `GeofencingManager.getRegisteredGeofenceIds()`
  Returns IDs for all registered geofences.

- `GeofencingManager.removeGeofenceById(id)` / `removeGeofence(region)`
  Removes a specific geofence.

- `GeofencingManager.removeAllGeofences()`
  Removes all geofences.

- `GeofencingManager.isSupported`
  `true` on Android/iOS.

- `GeofencingManager.maxGeofences`
  Platform‑specific limit.

## Android setup

### Manifest
Add to `AndroidManifest.xml`:

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

### Application class
Create `Application.kt` or `Application.java` alongside `MainActivity`:

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

or:

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

## iOS setup

### Info.plist
Add permission strings:

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

### Bridging header
In `Runner-Bridging-Header.h`:

```h
#import <geofencing/GeofencingPlugin.h>
```

### AppDelegate
In `AppDelegate.swift`:

```swift
GeofencingPlugin.setPluginRegistrantCallback { (registry) in
  GeneratedPluginRegistrant.register(with: registry)
}
```

## Permissions (recommended)
If you use `permission_handler`, add to `Podfile`:

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

## Example
See `example/lib/main.dart` for a working app with UI, permission handling, and local notifications.

## Issues and contributions
Please open an issue or PR if you find a bug or want to improve the plugin.

## Learn more
What is geofencing? See Android documentation:
https://developer.android.com/training/location/geofencing
