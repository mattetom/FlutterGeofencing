// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.flutter.FlutterInjector


class GeofencingBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "GeoBroadcastReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        // Timbro 1: l'istante in cui l'OS ci ha consegnato il broadcast. Tutto
        // cio' che sta fra il fix di posizione e questo momento e' fuori dal
        // nostro controllo; tutto cio' che viene dopo e' nostro. Senza questo
        // timbro le due tratte sono indistinguibili, ed e' esattamente
        // l'ambiguita' che ha reso invalida la conclusione "il ritardo e' tutto
        // di Play Services".
        intent.putExtra(GeofencingPlugin.RECEIVER_ENTRY_MS_KEY, System.currentTimeMillis())

        // Promozione a foreground PRIMA di accodare il lavoro. E' il punto
        // cruciale di tutta la catena.
        //
        // `enqueueWork` passa da JobIntentService, che su API 26+ e' costruito
        // sopra JobScheduler: se l'app e' inattiva, il lavoro accodato viene
        // differito da Doze e dagli App Standby Bucket. Misurato su un rientro
        // reale (Galaxy Z Fold6, Android 16, telefono parcheggiato e fermo):
        // Play Services ha consegnato il broadcast in 113 ms, e il lavoro e'
        // rimasto in coda **22 minuti e 57 secondi** prima di partire. Sullo
        // stesso device e la stessa geofence, un'uscita in auto con device in
        // movimento aveva 16 ms di coda: e' lo stato del device a decidere, ed
        // e' anche la spiegazione dell'asimmetria fra uscita e rientro.
        //
        // Con un foreground service attivo il processo ha importanza foreground
        // e i suoi job non subiscono quel differimento. Il plugin gia' esponeva
        // `promoteToForeground`, ma veniva invocata dal callback Dart, cioe'
        // DOPO che il job era finalmente partito: troppo tardi per servire a
        // qualcosa. Qui la promozione avviene mentre siamo ancora nella
        // temporary power allowlist concessa al PendingIntent del geofence, che
        // e' cio' che rende lecito avviare un FGS da background su Android 12+.
        //
        // Lo spegnimento resta a carico del consumatore a fine elaborazione
        // (`demoteToBackground`), come gia' avveniva. Se la promozione non e'
        // permessa si prosegue lo stesso: il comportamento degrada a quello
        // precedente, non peggiora.
        try {
            val holder = Intent(context, IsolateHolderService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(holder)
            } else {
                context.startService(holder)
            }
        } catch (t: Throwable) {
            // Comprende ForegroundServiceStartNotAllowedException (API 31+).
            Log.w(TAG, "Foreground promotion failed; falling back to plain enqueue", t)
        }

        Log.v(TAG, context.getString(R.string.geofence_triggered))

        val flutterLoader = FlutterInjector.instance().flutterLoader()
        flutterLoader.startInitialization(context)
        // Sincrono, sul main thread di un BroadcastReceiver: budget ANR ~10 s.
        flutterLoader.ensureInitializationComplete(context, null)

        // Timbro 2: dopo l'init del loader, prima di accodare. La differenza
        // col timbro 1 isola il costo dell'inizializzazione di Flutter, che
        // altrimenti verrebbe confusa con la coda dello scheduler.
        intent.putExtra(GeofencingPlugin.PRE_ENQUEUE_MS_KEY, System.currentTimeMillis())

        GeofencingService.enqueueWork(context, intent)
    }
}