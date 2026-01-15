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
        Log.v(TAG, context.getString(R.string.geofence_triggered))

        val flutterLoader = FlutterInjector.instance().flutterLoader()
        flutterLoader.startInitialization(context)
        flutterLoader.ensureInitializationComplete(context, null)

        GeofencingService.enqueueWork(context, intent)
    }
}