import 'dart:ui';

import 'package:flutter/services.dart';

import 'src/callback_dispatcher.dart';
import 'src/geofence_models.dart';

export 'src/geofence_models.dart';

/// Plugin di geofencing nativo (Android GeofencingClient, iOS Core Location
/// region monitoring) con callback Dart eseguito in background, anche ad
/// app terminata.
class FlutterGeofencePlugin {
  FlutterGeofencePlugin._();

  static const MethodChannel _channel =
      MethodChannel('com.matteotomasini.flutter_geofence_plugin/methods');

  /// Registra il [callback] da invocare a ogni transizione di geofence.
  ///
  /// Va chiamato a ogni avvio dell'app, prima di registrare aree.
  /// [callback] deve essere una funzione top-level o static annotata con
  /// `@pragma('vm:entry-point')`.
  static Future<void> initialize(GeofenceCallback callback) async {
    final dispatcher =
        PluginUtilities.getCallbackHandle(callbackDispatcher);
    final handle = PluginUtilities.getCallbackHandle(callback);
    if (handle == null) {
      throw ArgumentError(
          'callback must be a top-level or static function annotated with '
          "@pragma('vm:entry-point')");
    }
    await _channel.invokeMethod<void>('initialize', {
      'dispatcherHandle': dispatcher!.toRawHandle(),
      'callbackHandle': handle.toRawHandle(),
    });
  }

  /// Richiede i permessi di localizzazione necessari al geofencing in
  /// background ("sempre" / "allow all the time").
  ///
  /// Ritorna `true` se il permesso background è concesso. Su Android 11+
  /// la richiesta avviene in due passi (prima foreground, poi background,
  /// che apre le impostazioni di sistema): può servire richiamarla.
  static Future<bool> requestPermissions() async {
    final granted = await _channel.invokeMethod<bool>('requestPermissions');
    return granted ?? false;
  }

  /// Registra (o sostituisce, a parità di [GeofenceRegion.id]) un'area.
  ///
  /// Richiede [initialize] già chiamato e permessi concessi. Su iOS il
  /// sistema limita a 20 aree monitorate per app.
  static Future<void> registerGeofence(GeofenceRegion region) {
    return _channel.invokeMethod<void>('registerGeofence', region.toMap());
  }

  /// Rimuove l'area con l'[id] dato. Non fallisce se non esiste.
  static Future<void> removeGeofence(String id) {
    return _channel.invokeMethod<void>('removeGeofence', {'id': id});
  }

  /// Id delle aree attualmente registrate da questo plugin.
  static Future<List<String>> getRegisteredGeofenceIds() async {
    final ids =
        await _channel.invokeListMethod<String>('getRegisteredGeofenceIds');
    return ids ?? const [];
  }

  /// True se l'utente (o l'OS, es. dopo crash ripetuti) ha messo l'app in
  /// "Batteria → Con limitazioni" (background restricted): in quello stato
  /// Android SCARTA i broadcast diretti all'app — incluse le consegne delle
  /// geofence — in totale silenzio, e il plugin non puo' accorgersene da
  /// solo (nessun processo parte). Le app dovrebbero controllarlo a ogni
  /// apertura e avvisare l'utente. Sempre false su iOS e sotto Android 9.
  static Future<bool> isBackgroundRestricted() async {
    final v = await _channel.invokeMethod<bool>('isBackgroundRestricted');
    return v ?? false;
  }

  /// True se l'app e' esclusa dall'ottimizzazione batteria (whitelist Doze).
  /// Meno critico di [isBackgroundRestricted]: senza whitelist il geofencing
  /// funziona ma con latenze maggiori sotto Doze. Sempre true su iOS.
  static Future<bool> isIgnoringBatteryOptimizations() async {
    final v =
        await _channel.invokeMethod<bool>('isIgnoringBatteryOptimizations');
    return v ?? true;
  }

  static const MethodChannel _backgroundChannel = MethodChannel(
      'com.matteotomasini.flutter_geofence_plugin/background');

  /// Promuove il processo a foreground service (Android) per la durata di un
  /// callback lungo (es. chiamate di rete): l'OS non lo uccide a metà.
  ///
  /// Chiamabile SOLO dall'interno del callback di geofence (l'isolate in
  /// background): altrove lancia [MissingPluginException]. No-op su iOS.
  static Future<void> promoteToForeground() =>
      _backgroundChannel.invokeMethod<void>('promoteToForeground');

  /// Riporta il processo in background al termine del lavoro del callback.
  /// Stesse regole di [promoteToForeground].
  static Future<void> demoteToBackground() =>
      _backgroundChannel.invokeMethod<void>('demoteToBackground');
}
