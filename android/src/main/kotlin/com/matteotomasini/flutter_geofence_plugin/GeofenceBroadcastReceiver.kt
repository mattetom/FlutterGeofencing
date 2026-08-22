package com.matteotomasini.flutter_geofence_plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Riceve le transizioni di geofence da Play Services e le consegna
 * direttamente al callback Dart tramite [BackgroundEngineRunner].
 *
 * Dispatch diretto sotto `goAsync()`, di proposito senza JobScheduler /
 * WorkManager: una consegna accodata al job scheduler può restare in coda
 * per minuti sotto Doze, mentre il receiver viene eseguito subito.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofencing error code ${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        val eventBit = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> 1
            Geofence.GEOFENCE_TRANSITION_EXIT -> 2
            else -> return
        }

        val ids = event.triggeringGeofences?.map { it.requestId } ?: return
        if (ids.isEmpty()) return

        // Fallback sul centro della prima regione se la posizione puntuale
        // non è disponibile.
        val location = event.triggeringLocation
        val fallback = GeofencePersistence.getRegion(context, ids.first())
        val latitude = location?.latitude ?: fallback?.latitude ?: 0.0
        val longitude = location?.longitude ?: fallback?.longitude ?: 0.0

        val payload = mutableMapOf<String, Any>(
            "callbackHandle" to GeofencePersistence.getCallbackHandle(context),
            "ids" to ids,
            "event" to eventBit,
            "latitude" to latitude,
            "longitude" to longitude,
            "timestamp" to System.currentTimeMillis(),
        )
        if (location != null) {
            payload["fixTime"] = location.time
            payload["accuracy"] = location.accuracy.toDouble()
        }
        Log.i(TAG, "Geofence transition: event=$eventBit ids=$ids")

        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        val finish = {
            if (finished.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }
        // Il sistema concede ~10 s a un receiver asincrono: oltre quella
        // soglia rilasciamo il PendingResult; l'engine headless continua
        // comunque a eseguire il callback finché il processo vive.
        Handler(Looper.getMainLooper()).postDelayed(finish, 9_000L)

        BackgroundEngineRunner.dispatch(context, payload) { finish() }
    }

    private companion object {
        const val TAG = "GeofenceReceiver"
    }
}
