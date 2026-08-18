// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.JobIntentService
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.MethodCall
import io.flutter.view.FlutterCallbackInformation
import io.flutter.FlutterInjector
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback

import com.google.android.gms.location.GeofencingEvent

/**
 * Consegna degli eventi geofence all'isolate Dart di background.
 *
 * Dal 1.3.4 il percorso primario NON passa piu' da questo servizio: il
 * broadcast receiver chiama [dispatchDirect] sotto goAsync(). Il motivo e'
 * misurato: `enqueueWork` su API 26+ e' un job regolare di JobScheduler,
 * differito da Doze/App Standby esattamente quando il device e' fermo a
 * schermo spento (cioe' a ogni rientro a casa) — sul campo 22 e fino a 95
 * minuti fra `enqueueWork` e `onHandleWork`, contro 17-113 ms di consegna del
 * broadcast da parte di Play Services.
 *
 * Il JobIntentService resta come fallback esplicito se il dispatch diretto
 * fallisce, e per smaltire eventuali lavori gia' accodati da versioni
 * precedenti. Tutta la macchina condivisa (engine, canale, coda eventi) vive
 * nel companion, cosi' i due percorsi usano lo stesso stato e un evento non
 * puo' essere consegnato due volte.
 */
class GeofencingService : JobIntentService() {

    companion object {
        @JvmStatic
        private val TAG = "GeofencingService"
        // Stable across process restarts — MUST NOT be random. JobScheduler
        // persists queued JobWorkItems keyed by this id independently of the
        // app process; a value that changes on every cold start (previously
        // UUID.randomUUID(), regenerated whenever this class loads) leaves
        // stale work items under the old id when the process is killed and
        // restarted, and completeWork() then throws IllegalArgumentException
        // on a work item it doesn't recognize (see the host app's
        // docs/bug-jobintentservice-crash.md, issueId
        // a1a6fc986c63d0c941fb302d72cdd4bd). Derived from the class's
        // fully-qualified name (not a small literal like 1 or 1000) so it
        // stays deterministic across runs while staying unlikely to collide
        // with a job id chosen by another library in the same host app.
        @JvmStatic
        private val JOB_ID = "io.flutter.plugins.geofencing.GeofencingService".hashCode()
        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null
        @JvmStatic
        private val sServiceStarted = AtomicBoolean(false)

        // Safety net: if the background isolate never reports
        // `GeofencingService.initialized`, surface that so the host app can
        // show diagnostics / force a reset on next cold start.
        private const val ISOLATE_INIT_TIMEOUT_MS = 10_000L
        @JvmStatic
        private val sInitTimeoutHandler = Handler(Looper.getMainLooper())
        @JvmStatic
        private var sInitTimeoutRunnable: Runnable? = null

        // Eventi arrivati mentre l'isolate di background sta ancora partendo,
        // con l'eventuale azione di consegna (il receiver la usa per chiudere
        // il goAsync solo quando l'evento e' davvero in mano a Dart). Statica
        // e condivisa fra percorso diretto e fallback; era un campo d'istanza
        // del servizio, il che funzionava solo perche' il MethodChannel teneva
        // vivo il vecchio handler per riferimento.
        @JvmStatic
        private val sQueue = ArrayDeque<Pair<List<Any>, Runnable?>>()

        // Tetto difensivo: se l'isolate non parte mai la coda non deve
        // crescere senza limite (e' statica, vive quanto il processo).
        private const val MAX_QUEUED_EVENTS = 32

        @JvmStatic
        private var sBackgroundChannel: MethodChannel? = null
        @JvmStatic
        private var sContext: Context? = null

        // Handler del canale di background, statico: sopravvive alle istanze
        // del JobIntentService (create e distrutte per ogni batch di lavoro) e
        // serve anche il percorso diretto, dove nessun servizio esiste.
        @JvmStatic
        private val sMethodCallHandler = object : MethodCallHandler {
            override fun onMethodCall(call: MethodCall, result: Result) {
                when (call.method) {
                    "GeofencingService.initialized" -> {
                        synchronized(sServiceStarted) {
                            sInitTimeoutRunnable?.let { sInitTimeoutHandler.removeCallbacks(it) }
                            sInitTimeoutRunnable = null
                            while (!sQueue.isEmpty()) {
                                val (payload, onDelivered) = sQueue.remove()
                                sBackgroundChannel?.invokeMethod("", payload)
                                onDelivered?.run()
                            }
                            sServiceStarted.set(true)
                        }
                        result.success(null)
                    }
                    "GeofencingService.promoteToForeground" -> {
                        sContext?.let {
                            it.startForegroundService(Intent(it, IsolateHolderService::class.java))
                        }
                        result.success(null)
                    }
                    "GeofencingService.demoteToBackground" -> {
                        sContext?.let {
                            val intent = Intent(it, IsolateHolderService::class.java)
                            intent.setAction(IsolateHolderService.ACTION_SHUTDOWN)
                            it.startForegroundService(intent)
                        }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        }

        @JvmStatic
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(context, GeofencingService::class.java, JOB_ID, work)
        }

        @JvmStatic
        fun resetBackgroundEngine() {
            synchronized(sServiceStarted) {
                sInitTimeoutRunnable?.let { sInitTimeoutHandler.removeCallbacks(it) }
                sInitTimeoutRunnable = null
                sBackgroundFlutterEngine?.destroy()
                sBackgroundFlutterEngine = null
                sBackgroundChannel = null
                sServiceStarted.set(false)
            }
        }

        // Percorso primario: consegna l'evento senza passare da JobScheduler.
        // SOLO dal main thread (creazione engine e canale). Contratto:
        //  - ritorna true se e solo se l'evento e' stato PRESO IN CARICO
        //    (consegnato o accodato in attesa dell'isolate); solo in quel caso
        //    `onDelivered` verra' invocato — al piu' una volta, sul main
        //    thread, quando l'evento e' davvero in mano a Dart. Se l'isolate
        //    non parte mai, `onDelivered` non arriva: e' il watchdog del
        //    chiamante a chiudere il goAsync.
        //  - ritorna false se nessuna presa in carico e' avvenuta (nessun
        //    callback handle utilizzabile, o evento malformato): il chiamante
        //    puo' fare fallback su `enqueueWork` senza rischio di doppia
        //    consegna.
        @JvmStatic
        fun dispatchDirect(context: Context, intent: Intent, onDelivered: Runnable): Boolean {
            val appContext = context.applicationContext
            if (!ensureEngineStarted(appContext)) {
                return false
            }
            val payload = buildEventPayload(appContext, intent, System.currentTimeMillis())
                    ?: return false
            deliverOrQueue(payload, onDelivered)
            return true
        }

        // Avvia (se serve) l'engine Flutter di background e il canale verso il
        // callback dispatcher. SOLO dal main thread. A differenza del vecchio
        // `startGeofencingService`, la validazione del callback handle avviene
        // PRIMA di creare l'engine: un handle mancante o non risolvibile non
        // lascia piu' in giro un engine senza Dart (stato da cui non si
        // guariva, perche' il check `engine == null` risultava gia' falso), e
        // la prossima registrazione dal main isolate riscrive l'handle e
        // riprova da zero.
        @JvmStatic
        private fun ensureEngineStarted(context: Context): Boolean {
            synchronized(sServiceStarted) {
                sContext = context
                if (sBackgroundFlutterEngine != null) {
                    return true
                }

                val callbackHandle = context.getSharedPreferences(
                        GeofencingPlugin.SHARED_PREFERENCES_KEY,
                        Context.MODE_PRIVATE)
                        .getLong(GeofencingPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
                if (callbackHandle == 0L) {
                    Log.e(TAG, "Fatal: no callback registered")
                    return false
                }
                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                if (callbackInfo == null) {
                    Log.e(TAG, "Fatal: failed to find callback")
                    return false
                }
                Log.i(TAG, "Starting GeofencingService...")

                val flutterLoader = FlutterInjector.instance().flutterLoader()
                flutterLoader.startInitialization(context)
                flutterLoader.ensureInitializationComplete(context, null)

                val engine = FlutterEngine(context)
                sBackgroundFlutterEngine = engine
                val args = DartCallback(
                    context.getAssets(),
                    flutterLoader.findAppBundlePath(),
                    callbackInfo
                )
                engine.getDartExecutor().executeDartCallback(args)
                IsolateHolderService.setBackgroundFlutterEngine(engine)

                // Watchdog: if the Dart isolate doesn't report back as
                // initialized within ISOLATE_INIT_TIMEOUT_MS, emit a
                // diagnostic so the host app can notify the user / reset.
                sInitTimeoutRunnable?.let { sInitTimeoutHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (!sServiceStarted.get()) {
                        Log.e(TAG, "Background isolate did not initialize within ${ISOLATE_INIT_TIMEOUT_MS}ms")
                        GeofencingPlugin.emitDiagnostic(
                            "isolateInitTimeout",
                            mapOf("timeoutMs" to ISOLATE_INIT_TIMEOUT_MS)
                        )
                    }
                    sInitTimeoutRunnable = null
                }
                sInitTimeoutRunnable = runnable
                sInitTimeoutHandler.postDelayed(runnable, ISOLATE_INIT_TIMEOUT_MS)

                // Il canale va creato prima che il Dart appena lanciato invochi
                // `GeofencingService.initialized`: siamo sul main thread, che e'
                // anche il thread su cui arrivano le method call, quindi la
                // registrazione dell'handler non puo' perdere la corsa.
                sBackgroundChannel = MethodChannel(
                        engine.getDartExecutor().getBinaryMessenger(),
                        "plugins.flutter.io/geofencing_plugin_background")
                sBackgroundChannel!!.setMethodCallHandler(sMethodCallHandler)
                return true
            }
        }

        // Estrae il payload per il callback Dart dall'Intent di Play Services.
        // `handledAtMs` e' il momento della presa in carico: nel percorso
        // diretto coincide (a pochi ms) col pre-enqueue, nel fallback e'
        // l'avvio di onHandleWork — cosi' la tratta [1]->[2] dei timbri misura
        // rispettivamente l'handoff sincrono o la coda del JobScheduler.
        @JvmStatic
        private fun buildEventPayload(context: Context, intent: Intent, handledAtMs: Long): List<Any>? {
            val callbackHandle = intent.getLongExtra(GeofencingPlugin.CALLBACK_HANDLE_KEY, 0)
            val geofencingEvent = GeofencingEvent.fromIntent(intent)
            if (geofencingEvent == null || geofencingEvent.hasError()) {
                Log.e(TAG, "Geofencing error: ${geofencingEvent?.errorCode ?: "null"}")
                return null
            }

            // Get the transition type.
            val geofenceTransition = geofencingEvent.geofenceTransition

            // Get the geofences that were triggered. A single event can trigger
            // multiple geofences.
            val triggeringGeofences = geofencingEvent.triggeringGeofences?.map {
                it.requestId
            }

            val location = geofencingEvent.triggeringLocation
            val locationList = listOf(
                location?.latitude ?: 0,
                location?.longitude ?: 0
            )
            // 5th element: time of the GPS/network fix that satisfied the
            // transition, as int64 millis since epoch. 0 means "not provided"
            // (Dart side filters that out and surfaces null on Location.time).
            val triggerTimeMillis: Long = location?.time ?: 0L

            // 6th element: whether this is the synthetic initial-trigger event
            // Play Services delivers on (re)registration (cold start / app update /
            // reboot) rather than a real boundary crossing. True if any triggering
            // geofence fired within the initial-trigger window of its last
            // registration. The Dart callback can act on it (state recovery) while
            // NOT treating it as a flap or a state-change notification. iOS never
            // sends this element (no synthetic initial events), so the Dart side
            // defaults it to false.
            var isInitialTrigger = false
            for (id in triggeringGeofences ?: emptyList()) {
                if (GeofencingPlugin.consumeInitialTrigger(context, id)) {
                    isInitialTrigger = true
                }
            }

            // 7th element: i tre timbri della catena di consegna, in millisecondi
            // epoch. Permettono di dividere `os_to_callback_ms` nelle sue tratte:
            //   l.time -> [0]  = Play Services (fuori dal nostro controllo)
            //   [0] -> [1]     = init del Flutter loader, sincrona nel receiver
            //   [1] -> [2]     = handoff diretto (~0) oppure coda del
            //                    JobScheduler (solo nel percorso di fallback)
            //   [2] -> callback = avvio dell'isolate di background
            // Zero significa "non fornito" (percorsi che non passano dal receiver).
            val deliveryTimings = listOf(
                    intent.getLongExtra(GeofencingPlugin.RECEIVER_ENTRY_MS_KEY, 0L),
                    intent.getLongExtra(GeofencingPlugin.PRE_ENQUEUE_MS_KEY, 0L),
                    handledAtMs)

            return listOf<Any>(callbackHandle,
                    triggeringGeofences ?: emptyList<String>(),
                    locationList,
                    geofenceTransition,
                    triggerTimeMillis,
                    isInitialTrigger,
                    deliveryTimings)
        }

        @JvmStatic
        private fun deliverOrQueue(payload: List<Any>, onDelivered: Runnable?) {
            synchronized(sServiceStarted) {
                if (!sServiceStarted.get()) {
                    Log.i(TAG, "Queuing geofence event while background isolate is starting")
                    if (sQueue.size >= MAX_QUEUED_EVENTS) {
                        Log.w(TAG, "Event queue full, dropping oldest geofence event")
                        sQueue.remove().second?.run()
                    }
                    sQueue.add(Pair(payload, onDelivered))
                } else {
                    Log.i(TAG, "Sending geofence event to background isolate")
                    // Callback method name is intentionally left blank.
                    Handler(Looper.getMainLooper()).post {
                        sBackgroundChannel?.invokeMethod("", payload)
                        onDelivered?.run()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureEngineStarted(applicationContext)
    }

    // Percorso di FALLBACK (e smaltimento di lavori accodati da versioni
    // precedenti): gira solo se il dispatch diretto nel receiver non ha preso
    // in carico l'evento. Qui la tratta [1]->[2] dei timbri torna a misurare
    // la coda del JobScheduler, differita da Doze quando il device e' fermo.
    override fun onHandleWork(intent: Intent) {
        val handledAtMs = System.currentTimeMillis()
        Log.i(TAG, "onHandleWork")
        val payload = buildEventPayload(applicationContext, intent, handledAtMs) ?: return
        deliverOrQueue(payload, null)
    }
}
