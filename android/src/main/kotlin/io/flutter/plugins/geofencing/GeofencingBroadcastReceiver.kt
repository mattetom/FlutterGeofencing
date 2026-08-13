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
    }
}