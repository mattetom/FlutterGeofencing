// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:geofencing_service/src/callback_dispatcher.dart';
import 'package:geofencing_service/src/location.dart';
import 'package:geofencing_service/src/platform_settings.dart';

const int _kEnterEvent = 1;
const int _kExitEvent = 2;
const int _kDwellEvent = 4;

/// Maximum number of geofences allowed on iOS
const int kMaxGeofencesIOS = 20;

/// Maximum number of geofences allowed on Android
const int kMaxGeofencesAndroid = 100;

/// Minimum recommended radius for reliable geofence detection (meters)
const double kMinRecommendedRadius = 100.0;

/// Valid geofencing events.
///
/// Note: `GeofenceEvent.dwell` is not supported on iOS.
enum GeofenceEvent { enter, exit, dwell }

/// Exception thrown when geofencing operations fail
class GeofencingException implements Exception {
  final String message;
  final String? code;
  final dynamic details;

  GeofencingException(this.message, {this.code, this.details});

  @override
  String toString() => 'GeofencingException: $message${code != null ? ' (code: $code)' : ''}';
}

/// Categories of events emitted by [GeofencingManager.diagnostics].
///
/// `registrationRecovered` and `registrationFailedFinal` come from the
/// Android retry loop (transient `GEOFENCE_NOT_AVAILABLE` failures recovered
/// silently once services are back, or exhausted all retries).
///
/// `monitoringFailed` is emitted by iOS when `CLLocationManager`
/// asynchronously drops a region after it was accepted.
///
/// `isolateInitTimeout` is emitted on Android when the background Dart
/// isolate fails to report back within the watchdog window — this strongly
/// suggests the host app should reset the background engine on its next
/// cold start and investigate callback initialization.
enum GeofencingDiagnosticKind {
  registrationRecovered,
  registrationFailedFinal,
  monitoringFailed,
  isolateInitTimeout,
  unknown,
}

/// A diagnostic event surfaced by the native geofencing layer. Host apps can
/// subscribe to [GeofencingManager.diagnostics] to react (re-register,
/// notify the user, log to crash reporters) to states that would otherwise
/// be silent.
class GeofencingDiagnostic {
  final GeofencingDiagnosticKind kind;
  final String? id;
  final String? code;
  final String? message;
  final Map<String, dynamic> raw;

  GeofencingDiagnostic({
    required this.kind,
    this.id,
    this.code,
    this.message,
    this.raw = const {},
  });

  @override
  String toString() =>
      'GeofencingDiagnostic(${kind.name}, id=$id, code=$code, message=$message)';
}

// Internal.
int geofenceEventToInt(GeofenceEvent e) {
  switch (e) {
    case GeofenceEvent.enter:
      return _kEnterEvent;
    case GeofenceEvent.exit:
      return _kExitEvent;
    case GeofenceEvent.dwell:
      return _kDwellEvent;
  }
}

// Internal.
GeofenceEvent intToGeofenceEvent(int e) {
  switch (e) {
    case _kEnterEvent:
      return GeofenceEvent.enter;
    case _kExitEvent:
      return GeofenceEvent.exit;
    case _kDwellEvent:
      return GeofenceEvent.dwell;
    default:
      throw UnimplementedError('Unknown geofence event: $e');
  }
}

/// A circular region which represents a geofence.
class GeofenceRegion {
  /// The ID associated with the geofence.
  ///
  /// This ID is used to identify the geofence and is required to delete a
  /// specific geofence.
  final String id;

  /// The location of the geofence.
  final Location location;

  /// The radius around `location` that will be considered part of the geofence.
  final double radius;

  /// The types of geofence events to listen for.
  ///
  /// Note: `GeofenceEvent.dwell` is not supported on iOS.
  final List<GeofenceEvent> triggers;

  /// Android specific settings for a geofence.
  final AndroidGeofencingSettings androidSettings;

  GeofenceRegion(
    this.id,
    double latitude,
    double longitude,
    this.radius,
    this.triggers,
    this.androidSettings,
  ) : location = Location(latitude, longitude);

  List<dynamic> _toArgs() {
    final int triggerMask = triggers.fold(
        0, (int trigger, GeofenceEvent e) => (geofenceEventToInt(e) | trigger));
    final List<dynamic> args = <dynamic>[
      id,
      location.latitude,
      location.longitude,
      radius,
      triggerMask
    ];
    if (Platform.isAndroid) {
      args.addAll(platformSettingsToArgs(androidSettings));
    }
    return args;
  }
}

class GeofencingManager {
  static const MethodChannel _channel =
      MethodChannel('plugins.flutter.io/geofencing_plugin');
  static const MethodChannel _background =
      MethodChannel('plugins.flutter.io/geofencing_plugin_background');

  /// Whether the most recent event delivered to the geofence callback was a
  /// synthetic *initial-trigger* event — the state-sync Play Services emits on
  /// (re)registration on Android (cold start, app update, reboot) — rather than
  /// a real boundary crossing.
  ///
  /// Set by the callback dispatcher immediately before invoking your callback;
  /// read it **synchronously at the top of your callback** (before any
  /// `await`). It lets a callback act on the event for state recovery while not
  /// treating it as a real crossing (e.g. skip flap detection / a
  /// state-change notification). Always `false` on iOS, which only delivers
  /// real enter/exit events. Kept as a side-channel (not a new callback
  /// parameter) so the callback signature stays backward compatible.
  static bool lastEventWasInitialTrigger = false;

  /// Whether the plugin has been initialized
  static bool _initialized = false;

  /// Completer for initialization to prevent multiple concurrent initializations
  static Completer<void>? _initCompleter;

  static final StreamController<GeofencingDiagnostic> _diagnosticsController =
      StreamController<GeofencingDiagnostic>.broadcast();

  /// Stream of diagnostic events emitted by the native layer.
  ///
  /// Subscribe from the main isolate early in app startup (e.g. in `main()`
  /// after `GeofencingManager.initialize()`) so that events delivered while
  /// the app is foregrounded are observed and can be logged / surfaced to
  /// the user.
  static Stream<GeofencingDiagnostic> get diagnostics =>
      _diagnosticsController.stream;

  static bool _diagnosticsHandlerInstalled = false;

  static void _installDiagnosticsHandler() {
    if (_diagnosticsHandlerInstalled) return;
    _diagnosticsHandlerInstalled = true;
    _channel.setMethodCallHandler((MethodCall call) async {
      if (!call.method.startsWith('GeofencingPlugin.')) return null;
      final eventName = call.method.substring('GeofencingPlugin.'.length);
      final Map<String, dynamic> payload =
          (call.arguments is Map)
              ? Map<String, dynamic>.from(call.arguments as Map)
              : <String, dynamic>{};
      final kind = _kindFromEvent(eventName);
      _diagnosticsController.add(GeofencingDiagnostic(
        kind: kind,
        id: payload['id']?.toString(),
        code: payload['code']?.toString() ?? payload['errorCode']?.toString(),
        message: payload['message']?.toString(),
        raw: payload,
      ));
      return null;
    });
  }

  static GeofencingDiagnosticKind _kindFromEvent(String name) {
    switch (name) {
      case 'registrationRecovered':
        return GeofencingDiagnosticKind.registrationRecovered;
      case 'registrationFailedFinal':
        return GeofencingDiagnosticKind.registrationFailedFinal;
      case 'monitoringFailed':
        return GeofencingDiagnosticKind.monitoringFailed;
      case 'isolateInitTimeout':
        return GeofencingDiagnosticKind.isolateInitTimeout;
      default:
        return GeofencingDiagnosticKind.unknown;
    }
  }

  /// Initialize the plugin and request relevant permissions from the user.
  ///
  /// This method is safe to call multiple times - it will only initialize once.
  static Future<void> initialize() async {
    if (_initialized) return;

    // Prevent concurrent initialization
    if (_initCompleter != null) {
      return _initCompleter!.future;
    }

    _initCompleter = Completer<void>();

    try {
      _installDiagnosticsHandler();

      final CallbackHandle? callback =
          PluginUtilities.getCallbackHandle(callbackDispatcher);
      if (callback == null) {
        throw GeofencingException(
          'Failed to get callback handle for dispatcher',
          code: 'CALLBACK_HANDLE_ERROR',
        );
      }

      await _channel.invokeMethod('GeofencingPlugin.initializeService',
          <dynamic>[callback.toRawHandle()]);
      _initialized = true;
      _initCompleter!.complete();
    } catch (e) {
      _initCompleter!.completeError(e);
      _initCompleter = null;
      rethrow;
    }
  }
  
  /// Ensures the plugin is initialized before performing operations.
  /// 
  /// Automatically initializes if not already initialized.
  static Future<void> _ensureInitialized() async {
    if (!_initialized) {
      await initialize();
    }
  }

  /// Promote the geofencing service to a foreground service.
  ///
  /// Will throw an exception if called anywhere except for a geofencing
  /// callback.
  static Future<void> promoteToForeground() async =>
      await _background.invokeMethod('GeofencingService.promoteToForeground');

  /// Demote the geofencing service from a foreground service to a background
  /// service.
  ///
  /// Will throw an exception if called anywhere except for a geofencing
  /// callback.
  static Future<void> demoteToBackground() async =>
      await _background.invokeMethod('GeofencingService.demoteToBackground');

  /// Register for geofence events for a [GeofenceRegion].
  ///
  /// `region` is the geofence region to register with the system.
  /// `callback` is the method to be called when a geofence event associated
  /// with `region` occurs.
  ///
  /// Note: `GeofenceEvent.dwell` is not supported on iOS. If the
  /// `GeofenceRegion` provided only requests notifications for a
  /// `GeofenceEvent.dwell` trigger on iOS, `UnsupportedError` is thrown.
  /// 
  /// Throws [GeofencingException] if:
  /// - Invalid coordinates are provided
  /// - Maximum number of geofences is reached (20 on iOS, 100 on Android)
  /// - Geofencing service is not available
  static Future<void> registerGeofence(
      GeofenceRegion region,
      void Function(List<String> id, Location location, GeofenceEvent event)
          callback) async {
    // Auto-initialize if not already done
    await _ensureInitialized();
    
    // Validate platform-specific constraints
    if (Platform.isIOS &&
        region.triggers.contains(GeofenceEvent.dwell) &&
        (region.triggers.length == 1)) {
      throw UnsupportedError("iOS does not support 'GeofenceEvent.dwell'");
    }
    
    // Validate coordinates
    if (region.location.latitude < -90 || region.location.latitude > 90 ||
        region.location.longitude < -180 || region.location.longitude > 180) {
      throw GeofencingException(
        'Invalid coordinates: lat=${region.location.latitude}, lon=${region.location.longitude}',
        code: 'INVALID_COORDINATES',
      );
    }
    
    // Warn about small radius
    if (region.radius < kMinRecommendedRadius) {
      print('GeofencingManager: Warning - radius ${region.radius}m is below '
          'recommended minimum of ${kMinRecommendedRadius}m for reliable detection');
    }
    
    // Check platform-specific limits
    final currentGeofences = await getRegisteredGeofenceIds();
    final maxGeofences = Platform.isIOS ? kMaxGeofencesIOS : kMaxGeofencesAndroid;
    final isReplacing = currentGeofences.contains(region.id);
    
    if (!isReplacing && currentGeofences.length >= maxGeofences) {
      throw GeofencingException(
        'Maximum number of geofences ($maxGeofences) reached. '
        'Remove some geofences before adding new ones.',
        code: 'MAX_GEOFENCES_REACHED',
        details: {'current': currentGeofences.length, 'max': maxGeofences},
      );
    }
    
    final callbackHandle = PluginUtilities.getCallbackHandle(callback);
    if (callbackHandle == null) {
      throw GeofencingException(
        'Failed to get callback handle. Ensure callback is a top-level or static function.',
        code: 'CALLBACK_HANDLE_ERROR',
      );
    }
    
    final List<dynamic> args = <dynamic>[callbackHandle.toRawHandle()];
    args.addAll(region._toArgs());
    
    try {
      await _channel.invokeMethod('GeofencingPlugin.registerGeofence', args);
    } on PlatformException catch (e) {
      throw GeofencingException(
        e.message ?? 'Failed to register geofence',
        code: e.code,
        details: e.details,
      );
    }
  }

  /// Get all registered geofence identifiers.
  /// 
  /// Returns an empty list if no geofences are registered or if the plugin
  /// hasn't been initialized.
  static Future<List<String>> getRegisteredGeofenceIds() async {
    try {
      // Don't require initialization for this query
      final result = await _channel
          .invokeMethod('GeofencingPlugin.getRegisteredGeofenceIds');
      return List<String>.from(result ?? []);
    } catch (e) {
      print('GeofencingManager: Error getting registered geofence IDs: $e');
      return <String>[];
    }
  }

  /// Stop receiving geofence events for a given [GeofenceRegion].
  static Future<bool> removeGeofence(GeofenceRegion region) async =>
      await removeGeofenceById(region.id);

  /// Stop receiving geofence events for an identifier associated with a
  /// geofence region.
  /// 
  /// Returns true if the geofence was successfully removed, false if it
  /// didn't exist or couldn't be removed.
  static Future<bool> removeGeofenceById(String id) async {
    try {
      final result = await _channel
          .invokeMethod('GeofencingPlugin.removeGeofence', <dynamic>[id]);
      return result == true;
    } on PlatformException catch (e) {
      print('GeofencingManager: Error removing geofence "$id": ${e.message}');
      return false;
    }
  }
  
  /// Remove all registered geofences.
  /// 
  /// Returns the number of geofences successfully removed.
  static Future<int> removeAllGeofences() async {
    final ids = await getRegisteredGeofenceIds();
    int removed = 0;
    for (final id in ids) {
      if (await removeGeofenceById(id)) {
        removed++;
      }
    }
    return removed;
  }
  
  /// Check if the geofencing service is available on this device.
  /// 
  /// Returns true if geofencing can be used.
  static bool get isSupported => Platform.isAndroid || Platform.isIOS;
  
  /// Get the maximum number of geofences allowed on this platform.
  static int get maxGeofences => Platform.isIOS ? kMaxGeofencesIOS : kMaxGeofencesAndroid;

  /// Compare the geofences the host app expects to be registered with the
  /// ones the platform currently reports.
  ///
  /// Returns the IDs the host asked about, the subset that is actually
  /// registered right now, and the missing ones. Host apps should call this
  /// at cold start (after [initialize]) and re-register anything that comes
  /// back as missing — the SO can drop registrations silently on OS updates,
  /// user storage clear, or when location permissions are revoked/restored.
  ///
  /// Platform notes:
  /// - On iOS the "registered" list is read from
  ///   `CLLocationManager.monitoredRegions`, i.e. the real OS state.
  /// - On Android there is no public API to query the currently active
  ///   geofences from Play Services, so "registered" reflects this plugin's
  ///   persistent cache (updated on every successful register/remove).
  ///   Missing entries still warrant a re-registration, which is idempotent.
  static Future<GeofencingVerificationReport> verifyRegistrations(
      List<String> expected) async {
    final actual = await getRegisteredGeofenceIds();
    final actualSet = actual.toSet();
    final missing = <String>[
      for (final id in expected)
        if (!actualSet.contains(id)) id,
    ];
    return GeofencingVerificationReport(
      expected: List.unmodifiable(expected),
      registered: List.unmodifiable(actual),
      missing: List.unmodifiable(missing),
    );
  }

  /// Forcefully tear down the background Dart isolate on Android so the next
  /// geofence event spins up a fresh one. Useful as a recovery action when
  /// [GeofencingDiagnosticKind.isolateInitTimeout] has been observed.
  ///
  /// No-op on iOS (iOS manages the headless FlutterEngine itself and doesn't
  /// suffer from the same race).
  static Future<void> resetBackgroundEngine() async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('GeofencingPlugin.resetBackgroundEngine');
    } on PlatformException catch (e) {
      print('GeofencingManager: resetBackgroundEngine failed: ${e.message}');
    }
  }
}

/// Result of [GeofencingManager.verifyRegistrations].
class GeofencingVerificationReport {
  final List<String> expected;
  final List<String> registered;
  final List<String> missing;

  const GeofencingVerificationReport({
    required this.expected,
    required this.registered,
    required this.missing,
  });

  bool get isHealthy => missing.isEmpty;

  @override
  String toString() =>
      'GeofencingVerificationReport(expected=$expected, registered=$registered, missing=$missing)';
}
