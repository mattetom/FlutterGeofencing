package com.matteotomasini.flutter_geofence_plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation

/**
 * Esegue il callback Dart in un engine headless dedicato, avviato al primo
 * evento (anche ad app terminata) e riusato per quelli successivi.
 *
 * Gli eventi arrivati prima che l'isolate segnali
 * `backgroundIsolateInitialized` vengono accodati e consegnati appena pronto.
 *
 * Watchdog: un isolate che non si inizializza NON deve impantanare la coda
 * in silenzio (successe al plugin GeoCam originale: engine avviato, segnale
 * mai arrivato, eventi accumulati per sempre fino al riavvio del telefono).
 * Se l'init non arriva entro [ISOLATE_INIT_TIMEOUT_MS] l'engine viene
 * distrutto e ricreato: subito con un retry schedulato, e comunque al
 * prossimo evento. Dopo [MAX_CONSECUTIVE_FAILURES] fallimenti consecutivi
 * la coda viene svuotata (con log) per non trattenere PendingResult e
 * memoria a oltranza.
 */
internal object BackgroundEngineRunner {
    private const val TAG = "GeofenceBgRunner"
    private const val BACKGROUND_CHANNEL =
        "com.matteotomasini.flutter_geofence_plugin/background"
    private const val ISOLATE_INIT_TIMEOUT_MS = 15_000L
    private const val RETRY_DELAY_MS = 5_000L
    private const val MAX_PENDING = 32
    private const val MAX_CONSECUTIVE_FAILURES = 3

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var isolateReady = false
    private var watchdog: Runnable? = null
    private var consecutiveFailures = 0
    private val pending = mutableListOf<Pair<Map<String, Any>, () -> Unit>>()

    /**
     * Consegna [payload] al callback Dart; [onDone] viene invocato sul main
     * thread quando il callback ha finito (o subito, in caso di errore).
     */
    fun dispatch(context: Context, payload: Map<String, Any>, onDone: () -> Unit) {
        val ctx = context.applicationContext
        mainHandler.post {
            appContext = ctx
            if (isolateReady) {
                invoke(payload, onDone)
            } else {
                if (pending.size >= MAX_PENDING) {
                    Log.w(TAG, "Pending queue full, dropping oldest geofence event")
                    val (_, droppedDone) = pending.removeAt(0)
                    droppedDone()
                }
                pending.add(payload to onDone)
                startEngineIfNeeded(ctx)
            }
        }
    }

    private fun invoke(payload: Map<String, Any>, onDone: () -> Unit) {
        val ch = channel
        if (ch == null) {
            onDone()
            return
        }
        ch.invokeMethod("onGeofenceEvent", payload, object : MethodChannel.Result {
            override fun success(result: Any?) = onDone()
            override fun error(code: String, message: String?, details: Any?) {
                Log.e(TAG, "Dart geofence callback failed: $code $message")
                onDone()
            }

            override fun notImplemented() = onDone()
        })
    }

    private fun startEngineIfNeeded(context: Context) {
        if (engine != null) return // avvio già in corso o completato

        val dispatcherHandle = GeofencePersistence.getDispatcherHandle(context)
        if (dispatcherHandle == 0L) {
            Log.e(
                TAG,
                "No dispatcher handle stored: FlutterGeofencePlugin.initialize() " +
                    "was never called. Dropping geofence event.",
            )
            drainPendingWithoutDelivery()
            return
        }

        val loader = FlutterInjector.instance().flutterLoader()
        if (!loader.initialized()) {
            loader.startInitialization(context)
        }
        loader.ensureInitializationComplete(context, null)

        val callbackInfo =
            FlutterCallbackInformation.lookupCallbackInformation(dispatcherHandle)
        if (callbackInfo == null) {
            Log.e(TAG, "Stale callback handle $dispatcherHandle, dropping event.")
            drainPendingWithoutDelivery()
            return
        }

        Log.i(TAG, "Starting background Flutter engine")
        // La creazione dell'engine registra automaticamente i plugin del
        // GeneratedPluginRegistrant dell'app: nel callback Dart si possono
        // usare altri plugin (es. flutter_local_notifications).
        val newEngine = FlutterEngine(context)
        engine = newEngine

        val ch = MethodChannel(
            newEngine.dartExecutor.binaryMessenger,
            BACKGROUND_CHANNEL,
        )
        channel = ch
        ch.setMethodCallHandler { call, result ->
            when (call.method) {
                "backgroundIsolateInitialized" -> {
                    isolateReady = true
                    consecutiveFailures = 0
                    cancelWatchdog()
                    result.success(null)
                    Log.i(TAG, "Background isolate ready, delivering ${pending.size} event(s)")
                    val queued = pending.toList()
                    pending.clear()
                    for ((payload, onDone) in queued) {
                        invoke(payload, onDone)
                    }
                }

                "promoteToForeground" -> {
                    val ctx = appContext
                    if (ctx != null) GeofenceForegroundService.promote(ctx)
                    result.success(null)
                }

                "demoteToBackground" -> {
                    val ctx = appContext
                    if (ctx != null) GeofenceForegroundService.demote(ctx)
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }

        newEngine.dartExecutor.executeDartCallback(
            DartExecutor.DartCallback(
                context.assets,
                loader.findAppBundlePath(),
                callbackInfo,
            ),
        )

        val wd = Runnable { onIsolateInitTimeout(context) }
        watchdog = wd
        mainHandler.postDelayed(wd, ISOLATE_INIT_TIMEOUT_MS)
    }

    private fun onIsolateInitTimeout(context: Context) {
        if (isolateReady) return
        consecutiveFailures++
        Log.e(
            TAG,
            "Background isolate did not initialize within ${ISOLATE_INIT_TIMEOUT_MS} ms " +
                "(failure $consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): tearing down engine",
        )
        teardownEngine()
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "Giving up after $consecutiveFailures failed engine starts, dropping queue")
            consecutiveFailures = 0
            drainPendingWithoutDelivery()
            return
        }
        if (pending.isNotEmpty()) {
            mainHandler.postDelayed({
                if (!isolateReady && pending.isNotEmpty()) {
                    startEngineIfNeeded(context)
                }
            }, RETRY_DELAY_MS)
        }
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun teardownEngine() {
        cancelWatchdog()
        channel?.setMethodCallHandler(null)
        channel = null
        try {
            engine?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Engine destroy failed", e)
        }
        engine = null
        isolateReady = false
    }

    private fun drainPendingWithoutDelivery() {
        val queued = pending.toList()
        pending.clear()
        for ((_, onDone) in queued) {
            onDone()
        }
    }
}
