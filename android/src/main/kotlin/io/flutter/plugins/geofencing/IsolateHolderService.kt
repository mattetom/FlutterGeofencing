// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine

class IsolateHolderService : Service() {
    companion object {
        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"
        @JvmStatic
        val ACTION_START = "START"
        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"
        @JvmStatic
        private val TAG = "IsolateHolderService"
        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null

        @JvmStatic
        fun setBackgroundFlutterEngine(engine: FlutterEngine?) {
            sBackgroundFlutterEngine = engine
        }
    }

    override fun onBind(p0: Intent) : IBinder? {
        return null;
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "IsolateHolderService onCreate...")
        start()
    }

    private fun start() {
        val CHANNEL_ID = "geofencing"
        val channel = NotificationChannel(CHANNEL_ID,
                "Geofencing",
                NotificationManager.IMPORTANCE_NONE)
        val imageId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName())

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GeoBlink")
                .setContentText("Gofencing is enabled")
                .setSmallIcon(imageId)
                .setColor(0)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOnlyAlertOnce(true) // so when data is updated don't make sound and alert in android 8.0+
                .setOngoing(true)
                .build()

        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int) : Int {
        if (intent.getAction() == ACTION_SHUTDOWN) {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                    if (isHeld()) {
                        release()
                    }
                }
            }
            stopForeground(true)
            stopSelf()
        }
        if (intent.getAction() == ACTION_START) {
            start()
        }
        return START_STICKY;
    }
}
