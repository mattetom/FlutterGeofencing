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
import io.flutter.view.FlutterMain


class GeofencingBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "GeoBroadcastReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        Log.v(TAG, context.getString(R.string.geofence_triggered))
//        val fenceId = when {
//            geofencingEvent.triggeringGeofences?.isNotEmpty() == true ->
//                geofencingEvent.triggeringGeofences?.get(0)?.requestId
//
//            else -> {
//                Log.e(TAG, "No Geofence Trigger Found! Abort mission!")
//                return
//            }
//        }
//        val foundIndex = GeofencingConstants.LANDMARK_DATA.indexOfFirst {
//            it.id == fenceId
//        }
//
//        if (foundIndex == -1) {
//            Log.e(TAG, "Unknown Geofence: Abort Mission")
//            return
//        }

        // Get the local notification manager.
        val notificationManager = ContextCompat.getSystemService(
                context,
                NotificationManager::class.java
            ) as NotificationManager

        if (geofencingEvent.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            notificationManager.sendGeofenceEnteredNotification(
                context, 0
            )
        }

        if (geofencingEvent.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            notificationManager.sendGeofenceExitedNotification(
                context, 0
            )
        }
        FlutterMain.startInitialization(context)
        FlutterMain.ensureInitializationComplete(context, null)

        GeofencingService.enqueueWork(context, intent)
    }
}