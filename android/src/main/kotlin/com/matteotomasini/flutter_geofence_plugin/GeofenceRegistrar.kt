package com.matteotomasini.flutter_geofence_plugin

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Registrazione delle geofence su Play Services, condivisa tra il plugin
 * e il receiver di reboot.
 */
internal object GeofenceRegistrar {

    fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Play Services deve poter riempire l'intent con i dettagli
            // della transizione: da Android 12 serve FLAG_MUTABLE.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun addGeofence(
        context: Context,
        region: RegionData,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        var transitions = 0
        if (region.triggers and 1 != 0) {
            transitions = transitions or Geofence.GEOFENCE_TRANSITION_ENTER
        }
        if (region.triggers and 2 != 0) {
            transitions = transitions or Geofence.GEOFENCE_TRANSITION_EXIT
        }

        val geofence = Geofence.Builder()
            .setRequestId(region.id)
            .setCircularRegion(region.latitude, region.longitude, region.radius.toFloat())
            .setTransitionTypes(transitions)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

        var initial = 0
        if (region.initialTriggers and 1 != 0) {
            initial = initial or GeofencingRequest.INITIAL_TRIGGER_ENTER
        }
        if (region.initialTriggers and 2 != 0) {
            initial = initial or GeofencingRequest.INITIAL_TRIGGER_EXIT
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(initial)
            .addGeofence(geofence)
            .build()

        LocationServices.getGeofencingClient(context)
            .addGeofences(request, geofencePendingIntent(context))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun removeGeofence(context: Context, id: String, onDone: () -> Unit) {
        LocationServices.getGeofencingClient(context)
            .removeGeofences(listOf(id))
            .addOnCompleteListener { onDone() }
    }
}
