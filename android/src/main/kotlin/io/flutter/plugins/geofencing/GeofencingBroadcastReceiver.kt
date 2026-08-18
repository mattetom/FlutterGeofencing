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

        // Promozione a foreground DOPO aver accodato, e l'ordine e' il punto.
        //
        // Perche' serve: `enqueueWork` passa da JobIntentService, che su API 26+
        // e' costruito sopra JobScheduler, quindi il lavoro viene differito
        // quando l'app e' inattiva. Misurato su rientri reali: Play Services
        // consegna il broadcast in 81-113 ms e il lavoro resta in coda 23 e 95
        // minuti. Con un foreground service attivo il processo ha importanza
        // foreground e i suoi job non subiscono quel differimento.
        //
        // Perche' DOPO e non prima: `startForegroundService` impone che il
        // servizio chiami `startForeground()` entro ~5 s, e `onCreate` di un
        // Service gira sul MAIN THREAD, lo stesso su cui sta girando questo
        // `onReceive`. Chiamandola in cima, `onCreate` restava accodata dietro
        // `ensureInitializationComplete` (sincrona, su processo freddo anche
        // secondi) e la finestra scadeva: l'app veniva uccisa con
        // ForegroundServiceDidNotStartInTimeException a OGNI evento geofence,
        // cioe' arm e disarm smettevano del tutto di funzionare. Osservato su
        // un Galaxy Z Fold6: evento alle 22:43:28, crash alle 22:43:33.
        //
        // Spegnimento a carico del consumatore a fine elaborazione
        // (`demoteToBackground`), come gia' avveniva. Se la promozione non e'
        // permessa si prosegue: degrada al comportamento precedente.
        try {
            val holder = Intent(context, IsolateHolderService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(holder)
            } else {
                context.startService(holder)
            }
        } catch (t: Throwable) {
            // Comprende ForegroundServiceStartNotAllowedException (API 31+).
            Log.w(TAG, "Foreground promotion failed; work stays on the plain queue", t)
        }
    }
}