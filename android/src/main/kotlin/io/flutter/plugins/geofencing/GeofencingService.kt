// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
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
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback

import com.google.android.gms.location.GeofencingEvent

class GeofencingService : MethodCallHandler, JobIntentService() {
    private val queue = ArrayDeque<List<Any>>()
    private lateinit var mBackgroundChannel: MethodChannel
    private lateinit var mContext: Context

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
                sServiceStarted.set(false)
            }
        }
    }

    private fun startGeofencingService(context: Context) {
        synchronized(sServiceStarted) {
            mContext = context
            if (sBackgroundFlutterEngine == null) {
                sBackgroundFlutterEngine = FlutterEngine(context)

                val callbackHandle = context.getSharedPreferences(
                        GeofencingPlugin.SHARED_PREFERENCES_KEY,
                        Context.MODE_PRIVATE)
                        .getLong(GeofencingPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
                if (callbackHandle == 0L) {
                    Log.e(TAG, "Fatal: no callback registered")
                    return
                }

                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                if (callbackInfo == null) {
                    Log.e(TAG, "Fatal: failed to find callback")
                    return
                }
                Log.i(TAG, "Starting GeofencingService...")

                val flutterLoader = FlutterInjector.instance().flutterLoader()
                val args = DartCallback(
                    context.getAssets(),
                    flutterLoader.findAppBundlePath(),
                    callbackInfo
                )
                sBackgroundFlutterEngine!!.getDartExecutor().executeDartCallback(args)
                IsolateHolderService.setBackgroundFlutterEngine(sBackgroundFlutterEngine)

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
            }
        }
        mBackgroundChannel = MethodChannel(sBackgroundFlutterEngine!!.getDartExecutor().getBinaryMessenger(),
                "plugins.flutter.io/geofencing_plugin_background")
        mBackgroundChannel.setMethodCallHandler(this)
    }

   override fun onMethodCall(call: MethodCall, result: Result) {
       when(call.method) {
            "GeofencingService.initialized" -> {
                synchronized(sServiceStarted) {
                    sInitTimeoutRunnable?.let { sInitTimeoutHandler.removeCallbacks(it) }
                    sInitTimeoutRunnable = null
                    while (!queue.isEmpty()) {
                        mBackgroundChannel.invokeMethod("", queue.remove())
                    }
                    sServiceStarted.set(true)
                }
            }
            "GeofencingService.promoteToForeground" -> {
                mContext.startForegroundService(Intent(mContext, IsolateHolderService::class.java))
            }
            "GeofencingService.demoteToBackground" -> {
                val intent = Intent(mContext, IsolateHolderService::class.java)
                intent.setAction(IsolateHolderService.ACTION_SHUTDOWN)
                mContext.startForegroundService(intent)
            }
            else -> result.notImplemented()
        }
        result.success(null)
    }

    override fun onCreate() {
        super.onCreate()
        startGeofencingService(this)
    }

    override fun onHandleWork(intent: Intent) {
        // Timbro 3: il lavoro accodato e' finalmente partito. La differenza col
        // timbro 2 e' il tempo passato NELLA CODA DEL JOBSCHEDULER, perche'
        // JobIntentService su API 26+ e' implementato sopra JobScheduler ed e'
        // quindi soggetto a Doze e agli App Standby Bucket. E' il sospetto
        // principale per i ritardi di minuti osservati sugli enter.
        val handleWorkMs = System.currentTimeMillis()
        Log.i(TAG, "onHandleWork")
        val callbackHandle = intent.getLongExtra(GeofencingPlugin.CALLBACK_HANDLE_KEY, 0)
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null || geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent?.errorCode ?: "null"}")
            return
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
            if (GeofencingPlugin.consumeInitialTrigger(applicationContext, id)) {
                isInitialTrigger = true
            }
        }

        // 7th element: i tre timbri della catena di consegna, in millisecondi
        // epoch. Permettono di dividere `os_to_callback_ms` nelle sue tratte:
        //   l.time -> [0]  = Play Services (fuori dal nostro controllo)
        //   [0] -> [1]     = init del Flutter loader, sincrona nel receiver
        //   [1] -> [2]     = coda del JobScheduler
        //   [2] -> callback = avvio dell'isolate di background
        // Zero significa "non fornito" (percorsi che non passano dal receiver).
        val deliveryTimings = listOf(
                intent.getLongExtra(GeofencingPlugin.RECEIVER_ENTRY_MS_KEY, 0L),
                intent.getLongExtra(GeofencingPlugin.PRE_ENQUEUE_MS_KEY, 0L),
                handleWorkMs)

        val geofenceUpdateList = listOf<Any>(callbackHandle,
                triggeringGeofences ?: emptyList<String>(),
                locationList,
                geofenceTransition,
                triggerTimeMillis,
                isInitialTrigger,
                deliveryTimings)

        synchronized(sServiceStarted) {
            if (!sServiceStarted.get()) {
                Log.i(TAG, "Queuing geofence event while background isolate is starting")
                // Queue up geofencing events while background isolate is starting
                // NOTE: Don't wrap in extra list - send the same format as direct invocation
                queue.add(geofenceUpdateList)
            } else {
                Log.i(TAG, "Sending geofence event to background isolate")
                // Callback method name is intentionally left blank.
                Handler(mContext.mainLooper).post { mBackgroundChannel.invokeMethod("", geofenceUpdateList) }
            }
        }
    }
}
