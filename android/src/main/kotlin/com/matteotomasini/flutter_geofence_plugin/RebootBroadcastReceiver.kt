package com.matteotomasini.flutter_geofence_plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android rimuove tutte le geofence al reboot (e quando l'utente disattiva
 * la localizzazione): questo receiver le ri-registra dalla copia persistita.
 */
class RebootBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val regions = GeofencePersistence.getRegions(context)
        if (regions.isEmpty()) return

        // La ri-registrazione post-boot copre anche il ruolo del self-heal:
        // marca il boot così l'initialize() al prossimo avvio app non
        // ri-registra una seconda volta (azzererebbe di nuovo lo stato).
        val bootEpoch =
            System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        GeofencePersistence.markSelfHealDoneForBoot(context, bootEpoch)

        Log.i(TAG, "Re-registering ${regions.size} geofence(s) after reboot")
        for (region in regions) {
            GeofenceRegistrar.addGeofence(
                context,
                region,
                onSuccess = {},
                onFailure = { e ->
                    Log.e(TAG, "Failed to re-register geofence ${region.id}", e)
                },
            )
        }
    }

    private companion object {
        const val TAG = "GeofenceReboot"
    }
}
