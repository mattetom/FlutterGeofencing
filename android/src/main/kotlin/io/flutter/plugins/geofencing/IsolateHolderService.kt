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
        
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) : Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        // OGNI `startForegroundService()` va onorato da una `startForeground()`,
        // anche quando il servizio e' gia' in esecuzione: in quel caso `onCreate`
        // NON viene richiamato, quindi la chiamata deve stare qui.
        //
        // Prima stava solo in `onCreate` e bastava finche' l'avvio era uno solo
        // (il `promoteToForeground` del callback Dart). Con due avvii — es. il
        // broadcast receiver che promuove e poi il callback che ripromuove — il
        // secondo restava scoperto, e al successivo `demoteToBackground` il
        // sistema uccideva il processo con
        // ForegroundServiceDidNotStartInTimeException. Osservato su Galaxy Z
        // Fold6 (Android 16): l'app moriva a OGNI evento geofence, quindi arm e
        // disarm smettevano del tutto di funzionare.
        //
        // Vale anche per lo SHUTDOWN: se quell'intent e' la consegna di un
        // avvio ancora scoperto, bisogna prima onorarlo e poi spegnere.
        enterForeground()

        if (intent?.action == ACTION_SHUTDOWN) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    /// Costruisce la notifica e porta il servizio in foreground. Idempotente:
    /// richiamarla su un servizio gia' in foreground aggiorna la notifica senza
    /// effetti collaterali, ed e' cio' che permette di onorare avvii multipli.
    private fun enterForeground() {
        try {
            val notification = NotificationCompat.Builder(this, "geofencing_plugin_channel")
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setSmallIcon(smallIconResId())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
            startForeground(1, notification)
        } catch (e: Throwable) {
            // Se anche questa fallisce non c'e' modo di onorare il contratto:
            // meglio un log che un crash silenzioso da diagnosticare a valle.
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun smallIconResId(): Int {
        var id = resources.getIdentifier("ic_stat_notification", "drawable", packageName)
        if (id == 0) id = resources.getIdentifier("ic_launcher", "mipmap", packageName)
        return id
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
