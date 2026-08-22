import 'package:flutter/material.dart';
import 'package:geofencing_service/geofencing_service.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

final FlutterLocalNotificationsPlugin _notifications =
    FlutterLocalNotificationsPlugin();

Future<void> _initNotifications() async {
  const settings = InitializationSettings(
    android: AndroidInitializationSettings('@mipmap/ic_launcher'),
    iOS: DarwinInitializationSettings(
      requestAlertPermission: false,
      requestBadgePermission: false,
      requestSoundPermission: false,
    ),
  );
  await _notifications.initialize(settings: settings);
}

/// Callback di geofence: gira in un isolate in background, anche ad app
/// terminata. Deve essere top-level e annotato con vm:entry-point, e non
/// può toccare lo stato dell'app (qui re-inizializza le notifiche da zero).
@pragma('vm:entry-point')
Future<void> geofenceTriggered(GeofenceTriggerEvent event) async {
  await _initNotifications();

  final entered = event.type == GeofenceEventType.enter;
  const details = NotificationDetails(
    android: AndroidNotificationDetails(
      'geofence_events',
      'Eventi geofence',
      channelDescription: 'Notifiche di ingresso/uscita dalle aree',
      importance: Importance.high,
      priority: Priority.high,
    ),
    iOS: DarwinNotificationDetails(),
  );

  await _notifications.show(
    id: DateTime.now().millisecondsSinceEpoch & 0x7FFFFFFF,
    title: entered ? 'Sei entrato in un\'area' : 'Sei uscito da un\'area',
    body: '${entered ? 'Ingresso' : 'Uscita'}: ${event.regionIds.join(', ')}',
    notificationDetails: details,
  );
}

void main() {
  runApp(const GeofenceExampleApp());
}

class GeofenceExampleApp extends StatelessWidget {
  const GeofenceExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Geofence example',
      theme: ThemeData(colorSchemeSeed: Colors.teal),
      home: const GeofenceHomePage(),
    );
  }
}

class GeofenceHomePage extends StatefulWidget {
  const GeofenceHomePage({super.key});

  @override
  State<GeofenceHomePage> createState() => _GeofenceHomePageState();
}

class _GeofenceHomePageState extends State<GeofenceHomePage> {
  final _idController = TextEditingController(text: 'casa');
  final _latController = TextEditingController(text: '45.4642');
  final _lngController = TextEditingController(text: '9.1900');
  final _radiusController = TextEditingController(text: '200');
  bool _onEnter = true;
  bool _onExit = true;

  bool _permissionsGranted = false;
  List<String> _registeredIds = const [];
  String? _status;

  @override
  void initState() {
    super.initState();
    _setup();
  }

  Future<void> _setup() async {
    await _initNotifications();
    await _requestNotificationPermissions();
    await FlutterGeofencePlugin.initialize(geofenceTriggered);
    final granted = await FlutterGeofencePlugin.requestPermissions();
    final ids = await FlutterGeofencePlugin.getRegisteredGeofenceIds();
    if (!mounted) return;
    setState(() {
      _permissionsGranted = granted;
      _registeredIds = ids;
      _status = granted
          ? 'Permessi di localizzazione in background concessi.'
          : 'Serve il permesso "Consenti sempre" per il geofencing '
              'in background: riprova o concedilo dalle impostazioni.';
    });
  }

  Future<void> _requestNotificationPermissions() async {
    await _notifications
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.requestNotificationsPermission();
    await _notifications
        .resolvePlatformSpecificImplementation<
            IOSFlutterLocalNotificationsPlugin>()
        ?.requestPermissions(alert: true, badge: true, sound: true);
  }

  Future<void> _register() async {
    final lat = double.tryParse(_latController.text);
    final lng = double.tryParse(_lngController.text);
    final radius = double.tryParse(_radiusController.text);
    final id = _idController.text.trim();

    if (id.isEmpty || lat == null || lng == null || radius == null) {
      setState(() => _status = 'Compila id, latitudine, longitudine e raggio.');
      return;
    }
    if (!_onEnter && !_onExit) {
      setState(() => _status = 'Seleziona almeno una transizione.');
      return;
    }

    try {
      await FlutterGeofencePlugin.registerGeofence(GeofenceRegion(
        id: id,
        latitude: lat,
        longitude: lng,
        radiusMeters: radius,
        triggers: {
          if (_onEnter) GeofenceEventType.enter,
          if (_onExit) GeofenceEventType.exit,
        },
      ));
      final ids = await FlutterGeofencePlugin.getRegisteredGeofenceIds();
      setState(() {
        _registeredIds = ids;
        _status = 'Area "$id" registrata.';
      });
    } catch (e) {
      setState(() => _status = 'Registrazione fallita: $e');
    }
  }

  Future<void> _remove(String id) async {
    await FlutterGeofencePlugin.removeGeofence(id);
    final ids = await FlutterGeofencePlugin.getRegisteredGeofenceIds();
    setState(() {
      _registeredIds = ids;
      _status = 'Area "$id" rimossa.';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Geofence example')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (_status != null)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(_status!),
              ),
            ),
          if (!_permissionsGranted)
            FilledButton(
              onPressed: () async {
                final granted = await FlutterGeofencePlugin.requestPermissions();
                setState(() => _permissionsGranted = granted);
              },
              child: const Text('Richiedi permessi posizione'),
            ),
          const SizedBox(height: 16),
          TextField(
            controller: _idController,
            decoration: const InputDecoration(labelText: 'Id area'),
          ),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _latController,
                  decoration: const InputDecoration(labelText: 'Latitudine'),
                  keyboardType: const TextInputType.numberWithOptions(
                      decimal: true, signed: true),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: _lngController,
                  decoration: const InputDecoration(labelText: 'Longitudine'),
                  keyboardType: const TextInputType.numberWithOptions(
                      decimal: true, signed: true),
                ),
              ),
            ],
          ),
          TextField(
            controller: _radiusController,
            decoration: const InputDecoration(labelText: 'Raggio (metri)'),
            keyboardType: TextInputType.number,
          ),
          CheckboxListTile(
            value: _onEnter,
            onChanged: (v) => setState(() => _onEnter = v ?? false),
            title: const Text('Notifica all\'ingresso'),
            contentPadding: EdgeInsets.zero,
          ),
          CheckboxListTile(
            value: _onExit,
            onChanged: (v) => setState(() => _onExit = v ?? false),
            title: const Text('Notifica all\'uscita'),
            contentPadding: EdgeInsets.zero,
          ),
          FilledButton(
            onPressed: _register,
            child: const Text('Registra area'),
          ),
          const SizedBox(height: 24),
          Text('Aree registrate',
              style: Theme.of(context).textTheme.titleMedium),
          if (_registeredIds.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Text('Nessuna area registrata.'),
            ),
          for (final id in _registeredIds)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(id),
              trailing: IconButton(
                icon: const Icon(Icons.delete_outline),
                onPressed: () => _remove(id),
              ),
            ),
        ],
      ),
    );
  }
}
