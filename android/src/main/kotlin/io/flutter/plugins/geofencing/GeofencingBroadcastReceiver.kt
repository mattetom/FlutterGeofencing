// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import io.flutter.FlutterInjector


class GeofencingBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "GeoBroadcastReceiver"

    companion object {
        // goAsync() tiene vivo il broadcast (e il suo wakelock di sistema)
        // per ~10 s prima che sia il sistema a chiuderlo d'ufficio: chiudiamo
        // noi poco prima, cosi' la chiusura resta deterministica anche se
        // l'isolate di background non parte in tempo. L'evento in quel caso
        // NON si perde: e' gia' nella coda statica del dispatcher e viene
        // consegnato all'arrivo di `GeofencingService.initialized`.
        private const val FINISH_WATCHDOG_MS = 8_500L
    }

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

        // Timbro 2: dopo l'init del loader, prima della presa in carico. La
        // differenza col timbro 1 isola il costo dell'inizializzazione di
        // Flutter; la tratta successiva (timbro 2 -> timbro 3) e' l'handoff
        // diretto, oppure la coda del JobScheduler nel percorso di fallback.
        intent.putExtra(GeofencingPlugin.PRE_ENQUEUE_MS_KEY, System.currentTimeMillis())

        // Percorso primario: dispatch DIRETTO sotto goAsync(), senza passare
        // da JobIntentService. Il lavoro accodato via enqueueWork e' un job
        // regolare di JobScheduler, che Doze/App Standby differiscono di
        // minuti (misurati 22-95) proprio quando il device e' fermo a schermo
        // spento — cioe' a ogni rientro a casa — mentre questo broadcast
        // arriva in 17-113 ms. goAsync() mantiene processo e wakelock finche'
        // l'evento non e' davvero in mano a Dart (o scatta il watchdog), e la
        // temp-allowlist concessa dal broadcast di Play Services copre l'avvio
        // dell'engine anche a processo freddo.
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        val finishOnce = Runnable {
            if (finished.compareAndSet(false, true)) {
                try {
                    pendingResult.finish()
                } catch (t: Throwable) {
                    Log.w(TAG, "PendingResult.finish() failed: ${t.message}")
                }
            }
        }
        // Il watchdog E' finishOnce ritardato: se la consegna arriva prima, la
        // copia ritardata trova il flag gia' consumato e non fa nulla.
        Handler(Looper.getMainLooper()).postDelayed(finishOnce, FINISH_WATCHDOG_MS)

        val accepted = try {
            GeofencingService.dispatchDirect(context, intent, finishOnce)
        } catch (t: Throwable) {
            Log.e(TAG, "Direct dispatch failed, falling back to JobIntentService", t)
            false
        }
        if (!accepted) {
            // Fallback esplicito sul vecchio percorso. Sicuro contro le doppie
            // consegne: dispatchDirect ritorna false SOLO se l'evento non e'
            // stato preso in carico (ne' consegnato ne' accodato).
            try {
                GeofencingService.enqueueWork(context, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "enqueueWork fallback failed", t)
            }
            finishOnce.run()
        }

        // Promozione a foreground DOPO la presa in carico, e l'ordine e' il
        // punto.
        //
        // Perche' serve ancora, col dispatch diretto: il goAsync copre solo
        // fino alla consegna a Dart, ma il callback fa lavoro lungo (la
        // chiamata HTTP a Blink) DOPO quel momento, e se l'evento arriva
        // durante il Doze la rete e' preclusa alle app senza foreground
        // service. La promozione da' al processo importanza foreground per
        // tutta l'elaborazione; nel percorso di fallback, in piu', evita che
        // il job resti differito in coda.
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
            Log.w(TAG, "Foreground promotion failed; continuing without it", t)
        }
    }
}
