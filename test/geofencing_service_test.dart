import 'package:flutter/services.dart';
import 'package:geofencing_service/geofencing_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('GeofenceRegion', () {
    test('serializza i trigger come bitmask', () {
      final both = GeofenceRegion(
        id: 'a',
        latitude: 1,
        longitude: 2,
        radiusMeters: 100,
      );
      expect(both.triggerBitmask, 3);

      final enterOnly = GeofenceRegion(
        id: 'a',
        latitude: 1,
        longitude: 2,
        radiusMeters: 100,
        triggers: const {GeofenceEventType.enter},
      );
      expect(enterOnly.triggerBitmask, 1);

      final exitOnly = GeofenceRegion(
        id: 'a',
        latitude: 1,
        longitude: 2,
        radiusMeters: 100,
        triggers: const {GeofenceEventType.exit},
      );
      expect(exitOnly.triggerBitmask, 2);
    });

    test('toMap/fromMap fanno roundtrip', () {
      final region = GeofenceRegion(
        id: 'casa',
        latitude: 45.4642,
        longitude: 9.19,
        radiusMeters: 150,
        triggers: const {GeofenceEventType.exit},
      );
      final restored = GeofenceRegion.fromMap(region.toMap());
      expect(restored.id, region.id);
      expect(restored.latitude, region.latitude);
      expect(restored.longitude, region.longitude);
      expect(restored.radiusMeters, region.radiusMeters);
      expect(restored.triggers, region.triggers);
    });
  });

  group('GeofenceEventType.fromBitmask', () {
    test('mappa i valori del protocollo', () {
      expect(GeofenceEventType.fromBitmask(1), GeofenceEventType.enter);
      expect(GeofenceEventType.fromBitmask(2), GeofenceEventType.exit);
      expect(() => GeofenceEventType.fromBitmask(4), throwsArgumentError);
    });
  });

  group('MethodChannel', () {
    const channel =
        MethodChannel('com.matteotomasini.flutter_geofence_plugin/methods');
    final log = <MethodCall>[];

    setUp(() {
      log.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        log.add(call);
        if (call.method == 'getRegisteredGeofenceIds') {
          return <String>['casa', 'ufficio'];
        }
        return null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('registerGeofence invia il payload atteso', () async {
      await FlutterGeofencePlugin.registerGeofence(GeofenceRegion(
        id: 'casa',
        latitude: 45.0,
        longitude: 9.0,
        radiusMeters: 200,
      ));
      expect(log, hasLength(1));
      expect(log.single.method, 'registerGeofence');
      expect(log.single.arguments, {
        'id': 'casa',
        'latitude': 45.0,
        'longitude': 9.0,
        'radius': 200.0,
        'triggers': 3,
        'initialTriggers': 1,
      });
    });

    test('removeGeofence invia l\'id', () async {
      await FlutterGeofencePlugin.removeGeofence('casa');
      expect(log.single.method, 'removeGeofence');
      expect(log.single.arguments, {'id': 'casa'});
    });

    test('getRegisteredGeofenceIds ritorna la lista', () async {
      final ids = await FlutterGeofencePlugin.getRegisteredGeofenceIds();
      expect(ids, ['casa', 'ufficio']);
    });
  });
}
