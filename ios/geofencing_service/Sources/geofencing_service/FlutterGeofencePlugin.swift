import CoreLocation
import Flutter
import UIKit

/// Entry point iOS del plugin: region monitoring di Core Location con
/// callback Dart eseguito in un engine headless dedicato.
///
/// Quando l'app è terminata, iOS la rilancia in background al primo
/// attraversamento di regione: il plugin viene registrato al lancio, i
/// delegate di CLLocationManager arrivano e l'engine headless esegue il
/// `callbackDispatcher` Dart.
public class FlutterGeofencePlugin: NSObject, FlutterPlugin, CLLocationManagerDelegate {

    private static let methodChannelName =
        "com.matteotomasini.flutter_geofence_plugin/methods"
    private static let backgroundChannelName =
        "com.matteotomasini.flutter_geofence_plugin/background"
    private static let dispatcherKey = "flutter_geofence_plugin.dispatcher_handle"
    private static let callbackKey = "flutter_geofence_plugin.callback_handle"

    /// Registrant impostato dall'AppDelegate dell'app: serve a registrare i
    /// plugin di terze parti (es. flutter_local_notifications) sull'engine
    /// headless, così il callback Dart può usarli.
    private static var registerPlugins: FlutterPluginRegistrantCallback?

    /// Istanza unica: `register(with:)` viene chiamato sia per l'engine
    /// principale sia per quello headless, ma lo stato deve vivere una volta.
    private static var shared: FlutterGeofencePlugin?

    private let locationManager = CLLocationManager()
    private var permissionResult: FlutterResult?
    private var headlessEngine: FlutterEngine?
    private var backgroundChannel: FlutterMethodChannel?
    private var isolateReady = false
    private var pendingEvents: [[String: Any]] = []
    /// Regioni appena registrate per cui fare il check iniziale dello stato
    /// (equivalente degli INITIAL_TRIGGER di Android): id → bitmask degli
    /// initial trigger richiesti (1 = enter se già dentro, 2 = exit se fuori).
    private var pendingInitialCheck: [String: Int] = [:]
    /// Watchdog dell'engine headless: un isolate che non si inizializza non
    /// deve impantanare la coda eventi in silenzio.
    private var initWatchdog: DispatchWorkItem?
    private var consecutiveEngineFailures = 0
    /// Result di registerGeofence in attesa di didStartMonitoringFor:
    /// monitoredRegions si aggiorna in modo asincrono, rispondere prima
    /// farebbe vedere una lista stale a getRegisteredGeofenceIds.
    private var pendingRegistrations: [String: FlutterResult] = [:]
    /// Ultimo evento consegnato per regione: al rilancio in background iOS
    /// può riconsegnare la stessa transizione due volte, e una transizione
    /// ripetuta senza quella opposta in mezzo è sempre spuria.
    private var lastDeliveredEvent: [String: Int] = [:]

    public static func register(with registrar: FlutterPluginRegistrar) {
        if shared == nil {
            shared = FlutterGeofencePlugin()
        }
        let channel = FlutterMethodChannel(
            name: methodChannelName,
            binaryMessenger: registrar.messenger()
        )
        registrar.addMethodCallDelegate(shared!, channel: channel)
    }

    /// Da chiamare in `AppDelegate.didFinishLaunchingWithOptions`:
    /// `FlutterGeofencePlugin.setPluginRegistrantCallback { registry in
    ///     GeneratedPluginRegistrant.register(with: registry)
    /// }`
    public static func setPluginRegistrantCallback(
        _ callback: @escaping FlutterPluginRegistrantCallback
    ) {
        registerPlugins = callback
    }

    override init() {
        super.init()
        locationManager.delegate = self
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "initialize":
            let args = call.arguments as! [String: Any]
            let defaults = UserDefaults.standard
            defaults.set(args["dispatcherHandle"] as! NSNumber, forKey: Self.dispatcherKey)
            defaults.set(args["callbackHandle"] as! NSNumber, forKey: Self.callbackKey)
            result(nil)

        case "requestPermissions":
            requestPermissions(result: result)

        case "registerGeofence":
            registerGeofence(call: call, result: result)

        case "removeGeofence":
            let id = (call.arguments as! [String: Any])["id"] as! String
            for region in locationManager.monitoredRegions
            where region.identifier == id {
                locationManager.stopMonitoring(for: region)
            }
            pendingInitialCheck.removeValue(forKey: id)
            lastDeliveredEvent.removeValue(forKey: id)
            result(nil)

        case "isBackgroundRestricted":
            // Concetto solo Android: iOS non ha uno stato equivalente.
            result(false)

        case "isIgnoringBatteryOptimizations":
            result(true)

        case "getRegisteredGeofenceIds":
            result(locationManager.monitoredRegions.map { $0.identifier })

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // ------------------------------------------------------------------
    // Registrazione regioni
    // ------------------------------------------------------------------

    private func registerGeofence(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else {
            result(FlutterError(
                code: "unsupported",
                message: "Region monitoring is not available on this device.",
                details: nil
            ))
            return
        }

        let args = call.arguments as! [String: Any]
        let id = args["id"] as! String
        let latitude = (args["latitude"] as! NSNumber).doubleValue
        let longitude = (args["longitude"] as! NSNumber).doubleValue
        let radius = (args["radius"] as! NSNumber).doubleValue
        let triggers = (args["triggers"] as! NSNumber).intValue
        let initialTriggers = (args["initialTriggers"] as? NSNumber)?.intValue ?? 1

        let alreadyMonitored = locationManager.monitoredRegions
            .contains { $0.identifier == id }
        if !alreadyMonitored && locationManager.monitoredRegions.count >= 20 {
            result(FlutterError(
                code: "region_limit",
                message: "iOS limits region monitoring to 20 regions per app.",
                details: nil
            ))
            return
        }

        let region = CLCircularRegion(
            center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            radius: min(radius, locationManager.maximumRegionMonitoringDistance),
            identifier: id
        )
        region.notifyOnEntry = triggers & 1 != 0
        region.notifyOnExit = triggers & 2 != 0

        lastDeliveredEvent.removeValue(forKey: id)
        // La risposta arriva da didStartMonitoringFor / monitoringDidFailFor:
        // monitoredRegions si aggiorna solo a monitoring avviato.
        pendingRegistrations[id] = result
        locationManager.startMonitoring(for: region)

        // Check iniziale: consegna subito l'evento che riflette lo stato
        // corrente, se richiesto (parità con gli INITIAL_TRIGGER di Android).
        // didDetermineState scarta gli id non in pending.
        if initialTriggers != 0 {
            pendingInitialCheck[id] = initialTriggers
            locationManager.requestState(for: region)
        }
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didStartMonitoringFor region: CLRegion
    ) {
        pendingRegistrations.removeValue(forKey: region.identifier)?(nil)
    }

    // ------------------------------------------------------------------
    // Permessi
    // ------------------------------------------------------------------

    private var authorizationStatus: CLAuthorizationStatus {
        if #available(iOS 14.0, *) {
            return locationManager.authorizationStatus
        }
        return CLLocationManager.authorizationStatus()
    }

    private func requestPermissions(result: @escaping FlutterResult) {
        switch authorizationStatus {
        case .authorizedAlways:
            result(true)
        case .notDetermined, .authorizedWhenInUse:
            permissionResult = result
            locationManager.requestAlwaysAuthorization()
        default:
            result(false)
        }
    }

    private func authorizationChanged(to status: CLAuthorizationStatus) {
        guard status != .notDetermined, let pending = permissionResult else { return }
        permissionResult = nil
        pending(status == .authorizedAlways)
    }

    @available(iOS 14.0, *)
    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationChanged(to: manager.authorizationStatus)
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didChangeAuthorization status: CLAuthorizationStatus
    ) {
        authorizationChanged(to: status)
    }

    // ------------------------------------------------------------------
    // Eventi di regione
    // ------------------------------------------------------------------

    public func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        pendingInitialCheck.removeValue(forKey: region.identifier)
        dispatchEvent(region: region, eventBit: 1)
    }

    public func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        pendingInitialCheck.removeValue(forKey: region.identifier)
        dispatchEvent(region: region, eventBit: 2)
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didDetermineState state: CLRegionState,
        for region: CLRegion
    ) {
        // Solo il check iniziale post-registrazione: le transizioni vere
        // passano da didEnter/didExitRegion.
        guard let mask = pendingInitialCheck[region.identifier] else { return }
        // Il primo callback può arrivare con stato unknown: aspetta una
        // determinazione vera prima di consumare il check.
        guard state != .unknown else { return }
        pendingInitialCheck.removeValue(forKey: region.identifier)
        if state == .inside && mask & 1 != 0 {
            dispatchEvent(region: region, eventBit: 1)
        } else if state == .outside && mask & 2 != 0 {
            dispatchEvent(region: region, eventBit: 2)
        }
    }

    public func locationManager(
        _ manager: CLLocationManager,
        monitoringDidFailFor region: CLRegion?,
        withError error: Error
    ) {
        NSLog("flutter_geofence_plugin: monitoring failed for %@: %@",
              region?.identifier ?? "?", error.localizedDescription)
        if let id = region?.identifier,
           let pending = pendingRegistrations.removeValue(forKey: id) {
            pending(FlutterError(
                code: "monitoring_failed",
                message: error.localizedDescription,
                details: nil
            ))
        }
    }

    private func dispatchEvent(region: CLRegion, eventBit: Int) {
        // iOS notifica solo i cambi di stato: la stessa transizione ripetuta
        // per la stessa regione (tipico alla riconsegna post-rilancio in
        // background) è un duplicato da scartare.
        if lastDeliveredEvent[region.identifier] == eventBit { return }
        lastDeliveredEvent[region.identifier] = eventBit

        let defaults = UserDefaults.standard
        let callbackHandle = (defaults.object(forKey: Self.callbackKey) as? NSNumber)?
            .int64Value ?? 0
        guard callbackHandle != 0 else {
            NSLog("flutter_geofence_plugin: no callback registered, dropping event")
            return
        }

        var latitude = 0.0
        var longitude = 0.0
        var accuracy: Double?
        if let location = locationManager.location {
            latitude = location.coordinate.latitude
            longitude = location.coordinate.longitude
            accuracy = location.horizontalAccuracy
        } else if let circular = region as? CLCircularRegion {
            latitude = circular.center.latitude
            longitude = circular.center.longitude
        }

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        var payload: [String: Any] = [
            "callbackHandle": NSNumber(value: callbackHandle),
            "ids": [region.identifier],
            "event": eventBit,
            "latitude": latitude,
            "longitude": longitude,
            "timestamp": NSNumber(value: nowMs),
            // CLRegion non porta il timestamp del fix: si usa l'istante del
            // delegate, stessa semantica del plugin storico.
            "fixTime": NSNumber(value: nowMs),
        ]
        if let accuracy {
            payload["accuracy"] = accuracy
        }

        if isolateReady {
            backgroundChannel?.invokeMethod("onGeofenceEvent", arguments: payload)
        } else {
            if pendingEvents.count >= 32 {
                NSLog("flutter_geofence_plugin: pending queue full, dropping oldest")
                pendingEvents.removeFirst()
            }
            pendingEvents.append(payload)
            startHeadlessEngineIfNeeded()
        }
    }

    // ------------------------------------------------------------------
    // Engine headless
    // ------------------------------------------------------------------

    private func startHeadlessEngineIfNeeded() {
        guard headlessEngine == nil else { return } // avvio già in corso

        let defaults = UserDefaults.standard
        let dispatcherHandle = (defaults.object(forKey: Self.dispatcherKey) as? NSNumber)?
            .int64Value ?? 0
        guard dispatcherHandle != 0,
              let info = FlutterCallbackCache.lookupCallbackInformation(dispatcherHandle)
        else {
            NSLog("flutter_geofence_plugin: invalid dispatcher handle, dropping events")
            pendingEvents.removeAll()
            return
        }

        let engine = FlutterEngine(
            name: "flutter_geofence_plugin",
            project: nil,
            allowHeadlessExecution: true
        )
        headlessEngine = engine
        engine.run(withEntrypoint: info.callbackName, libraryURI: info.callbackLibraryPath)
        Self.registerPlugins?(engine)

        let channel = FlutterMethodChannel(
            name: Self.backgroundChannelName,
            binaryMessenger: engine.binaryMessenger
        )
        backgroundChannel = channel
        channel.setMethodCallHandler { [weak self] call, result in
            guard let self else { return result(FlutterMethodNotImplemented) }
            switch call.method {
            case "backgroundIsolateInitialized":
                self.isolateReady = true
                self.consecutiveEngineFailures = 0
                self.initWatchdog?.cancel()
                self.initWatchdog = nil
                result(nil)
                let queued = self.pendingEvents
                self.pendingEvents.removeAll()
                for payload in queued {
                    self.backgroundChannel?.invokeMethod("onGeofenceEvent", arguments: payload)
                }
            case "promoteToForeground", "demoteToBackground":
                // Solo Android: su iOS la finestra di background è gestita
                // dal sistema al rilancio per evento di regione.
                result(nil)
            default:
                result(FlutterMethodNotImplemented)
            }
        }

        let wd = DispatchWorkItem { [weak self] in self?.onIsolateInitTimeout() }
        initWatchdog = wd
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: wd)
    }

    /// Un engine avviato il cui isolate non segnala mai l'init non deve
    /// impantanare la coda in silenzio: buttalo e riprova (al prossimo evento
    /// e con un retry schedulato), arrendendosi dopo 3 fallimenti di fila.
    private func onIsolateInitTimeout() {
        guard !isolateReady else { return }
        consecutiveEngineFailures += 1
        NSLog("flutter_geofence_plugin: background isolate init timeout (failure %d/3)",
              consecutiveEngineFailures)
        backgroundChannel?.setMethodCallHandler(nil)
        backgroundChannel = nil
        headlessEngine?.destroyContext()
        headlessEngine = nil
        initWatchdog = nil
        if consecutiveEngineFailures >= 3 {
            NSLog("flutter_geofence_plugin: giving up, dropping %d queued event(s)",
                  pendingEvents.count)
            pendingEvents.removeAll()
            consecutiveEngineFailures = 0
            return
        }
        if !pendingEvents.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                self?.startHeadlessEngineIfNeeded()
            }
        }
    }
}
