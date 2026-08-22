/// Tipo di transizione di geofence.
enum GeofenceEventType {
  /// Ingresso nell'area.
  enter(1),

  /// Uscita dall'area.
  exit(2);

  const GeofenceEventType(this.bitmask);

  /// Valore usato nel protocollo di piattaforma (bitmask, combinabile).
  final int bitmask;

  static GeofenceEventType fromBitmask(int value) {
    switch (value) {
      case 1:
        return GeofenceEventType.enter;
      case 2:
        return GeofenceEventType.exit;
      default:
        throw ArgumentError.value(value, 'value', 'Unknown geofence event');
    }
  }
}

/// Un'area circolare di geofencing da monitorare.
class GeofenceRegion {
  GeofenceRegion({
    required this.id,
    required this.latitude,
    required this.longitude,
    required this.radiusMeters,
    this.triggers = const {GeofenceEventType.enter, GeofenceEventType.exit},
    this.initialTriggers = const {GeofenceEventType.enter},
  })  : assert(id.isNotEmpty, 'id must not be empty'),
        assert(radiusMeters > 0, 'radiusMeters must be positive'),
        assert(triggers.isNotEmpty, 'at least one trigger is required');

  /// Identificativo univoco dell'area. Registrare due volte lo stesso id
  /// sostituisce la regione precedente.
  final String id;

  final double latitude;
  final double longitude;

  /// Raggio in metri. Su iOS il massimo consentito dal sistema è
  /// `CLLocationManager.maximumRegionMonitoringDistance` (~400 km);
  /// in pratica sotto i 100 m l'affidabilità cala su entrambe le piattaforme.
  final double radiusMeters;

  /// Per quali transizioni ricevere il callback.
  final Set<GeofenceEventType> triggers;

  /// Evento sintetico consegnato subito dopo la registrazione se lo stato
  /// corrente del dispositivo corrisponde (es. `enter` se è già dentro
  /// l'area). Utile come recovery dello stato alla ri-registrazione; vuoto
  /// = nessun evento iniziale, solo attraversamenti reali.
  final Set<GeofenceEventType> initialTriggers;

  int get triggerBitmask =>
      triggers.fold(0, (mask, t) => mask | t.bitmask);

  int get initialTriggerBitmask =>
      initialTriggers.fold(0, (mask, t) => mask | t.bitmask);

  Map<String, Object> toMap() => {
        'id': id,
        'latitude': latitude,
        'longitude': longitude,
        'radius': radiusMeters,
        'triggers': triggerBitmask,
        'initialTriggers': initialTriggerBitmask,
      };

  static GeofenceRegion fromMap(Map<Object?, Object?> map) {
    final triggerMask = map['triggers'] as int;
    final initialMask = (map['initialTriggers'] as int?) ?? 1;
    return GeofenceRegion(
      id: map['id'] as String,
      latitude: (map['latitude'] as num).toDouble(),
      longitude: (map['longitude'] as num).toDouble(),
      radiusMeters: (map['radius'] as num).toDouble(),
      triggers: {
        for (final t in GeofenceEventType.values)
          if (triggerMask & t.bitmask != 0) t,
      },
      initialTriggers: {
        for (final t in GeofenceEventType.values)
          if (initialMask & t.bitmask != 0) t,
      },
    );
  }
}

/// Evento consegnato al callback in background quando il dispositivo
/// entra o esce da una o più aree registrate.
class GeofenceTriggerEvent {
  GeofenceTriggerEvent({
    required this.regionIds,
    required this.type,
    required this.latitude,
    required this.longitude,
    required this.timestamp,
    this.fixTimestamp,
    this.accuracy,
  });

  /// Id delle aree coinvolte. Su Android una singola transizione può
  /// riguardare più aree sovrapposte; su iOS è sempre una sola.
  final List<String> regionIds;

  final GeofenceEventType type;

  /// Posizione che ha causato la transizione (su iOS può essere il centro
  /// della regione se la posizione puntuale non è disponibile).
  final double latitude;
  final double longitude;

  /// Momento in cui il nativo ha ricevuto l'evento dall'OS (ingresso nel
  /// BroadcastReceiver su Android, delegate su iOS).
  final DateTime timestamp;

  /// Momento in cui è stato prodotto il fix di posizione che ha innescato
  /// la transizione (Android: `triggeringLocation.time`; iOS: istante del
  /// delegate, CLRegion non porta un timestamp). La differenza con "adesso"
  /// misura il ritardo di consegna dell'OS. Null se non disponibile.
  final DateTime? fixTimestamp;

  /// Accuratezza orizzontale in metri del fix che ha innescato la
  /// transizione, se disponibile.
  final double? accuracy;

  @override
  String toString() =>
      'GeofenceTriggerEvent(${type.name} ${regionIds.join(",")} '
      '@ $latitude,$longitude $timestamp)';
}

/// Firma del callback invocato in background a ogni transizione.
///
/// Deve essere una funzione top-level o static, annotata con
/// `@pragma('vm:entry-point')`: viene eseguita in un isolate dedicato,
/// anche ad app terminata, quindi non può dipendere da stato dell'app.
typedef GeofenceCallback = Future<void> Function(GeofenceTriggerEvent event);
