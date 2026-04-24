# Example: `geofencing_service`

Sample app that uses the [`geofencing_service`](../README.md) plugin via `path: ../` in this repository.

## What it does
- Requests location permissions (when in use, and “always” where required).
- Initializes `GeofencingManager` and registers one or more test regions.
- Demonstrates the APIs and, depending on the code, local notifications when regions are entered or left.

## Running
From the `example/` directory:

```bash
flutter pub get
flutter run
```

### iOS
After `flutter pub get`, when you change the plugin or native dependencies:

```bash
cd ios
pod install
cd ..
```

The sample already wires `AppDelegate` / `Info.plist` for `GeofencingPlugin` — see the [plugin README](../README.md#ios).

## Supporting dependencies
The example typically includes `permission_handler` and, when used, `flutter_local_notifications` to make background events obvious. They are not required by the package itself, only for the demo.

## Full documentation
See the [main plugin README](../README.md).
