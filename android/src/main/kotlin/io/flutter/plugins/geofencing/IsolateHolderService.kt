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
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"
        @JvmStatic
        private val TAG = "IsolateHolderService"
        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null
        
        // Configurable notification content
        @JvmStatic
        var notificationTitle: String = "Geofencing Active"
        @JvmStatic
        var notificationText: String = "Location monitoring is running in background."
        @JvmStatic
        var notificationChannelName: String = "Geofencing Service"

        @JvmStatic
        fun setBackgroundFlutterEngine(engine: FlutterEngine?) {
            sBackgroundFlutterEngine = engine
        }
        
        @JvmStatic
        fun configureNotification(title: String, text: String, channelName: String? = null) {
            notificationTitle = title
            notificationText = text
            if (channelName != null) {
                notificationChannelName = channelName
            }
        }
    }
    
    // Store WakeLock as member variable to properly manage its lifecycle
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(p0: Intent) : IBinder? {
        Log.i(TAG, "onBind")
        return null
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate")
        super.onCreate()
        
        val CHANNEL_ID = "geofencing_plugin_channel"
        val channel = NotificationChannel(
            CHANNEL_ID,
            notificationChannelName,
            NotificationManager.IMPORTANCE_LOW
        )
        
        // Try to get custom icon, fall back to launcher icon
        var imageId = resources.getIdentifier("ic_stat_notification", "drawable", packageName)
        if (imageId == 0) {
            imageId = resources.getIdentifier("ic_launcher", "mipmap", packageName)
        }

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setSmallIcon(imageId)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // Acquire WakeLock and store reference for proper release
        try {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L) // 10 minutes max to prevent battery drain
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
        
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) : Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        
        if (intent?.action == ACTION_SHUTDOWN) {
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        releaseWakeLock()
        super.onDestroy()
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }
}
