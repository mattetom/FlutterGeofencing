import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'geofence_models.dart';

const MethodChannel _backgroundChannel = MethodChannel(
    'com.matteotomasini.flutter_geofence_plugin/background');

/// Entrypoint eseguito dal codice nativo in un isolate dedicato quando
/// arriva un evento di geofence (anche ad app terminata).
///
/// Non chiamare direttamente: viene avviato dal plugin nativo tramite il
/// callback handle registrato con [FlutterGeofencePlugin.initialize].
@pragma('vm:entry-point')
void callbackDispatcher() {
  WidgetsFlutterBinding.ensureInitialized();

  _backgroundChannel.setMethodCallHandler((call) async {
    if (call.method != 'onGeofenceEvent') {
      throw UnimplementedError('${call.method} not implemented');
    }

    final args = call.arguments as Map<Object?, Object?>;
    final handle = CallbackHandle.fromRawHandle(args['callbackHandle'] as int);
    final callback = PluginUtilities.getCallbackFromHandle(handle);
    if (callback == null) {
      // L'app è stata aggiornata e il vecchio handle non è più valido:
      // l'evento viene perso, ma non deve far crashare l'isolate.
      return;
    }

    final fixTime = args['fixTime'] as int?;
    final event = GeofenceTriggerEvent(
      regionIds: (args['ids'] as List<Object?>).cast<String>(),
      type: GeofenceEventType.fromBitmask(args['event'] as int),
      latitude: (args['latitude'] as num).toDouble(),
      longitude: (args['longitude'] as num).toDouble(),
      timestamp:
          DateTime.fromMillisecondsSinceEpoch(args['timestamp'] as int),
      fixTimestamp: fixTime == null || fixTime <= 0
          ? null
          : DateTime.fromMillisecondsSinceEpoch(fixTime, isUtc: true),
      accuracy: (args['accuracy'] as num?)?.toDouble(),
    );

    await (callback as GeofenceCallback)(event);
  });

  // Segnala al nativo che l'isolate è pronto: gli eventi accodati
  // prima di questo momento vengono consegnati ora.
  _backgroundChannel.invokeMethod<void>('backgroundIsolateInitialized');
}
