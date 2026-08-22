package com.matteotomasini.flutter_geofence_plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service che tiene vivo il processo mentre il callback Dart fa
 * lavoro lungo (es. chiamate di rete). Avviato da `promoteToForeground` e
 * fermato da `demoteToBackground`, entrambi invocabili solo dal callback.
 *
 * L'avvio dal background è permesso perché al momento della promozione il
 * processo è nella temporary allowlist (broadcast del PendingIntent di
 * geofencing). Contratto Android: OGNI startForegroundService deve essere
 * onorata da una startForeground, quindi onStartCommand la chiama sempre
 * (tranne che per l'azione di stop).
 */
class GeofenceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Geofencing",
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }

        val appLabel = packageManager.getApplicationLabel(applicationInfo).toString()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(appLabel)
            .setContentText("Processing geofence event…")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "GeofenceFgService"
        private const val CHANNEL_ID = "flutter_geofence_plugin_service"
        private const val NOTIFICATION_ID = 24847
        private const val ACTION_STOP =
            "com.matteotomasini.flutter_geofence_plugin.STOP_FOREGROUND"

        fun promote(context: Context) {
            val intent = Intent(context, GeofenceForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Fuori dalla allowlist temporanea l'OS può rifiutare l'avvio:
                // il callback continua senza protezione foreground.
                Log.w(TAG, "promoteToForeground rejected by OS", e)
            }
        }

        fun demote(context: Context) {
            val intent = Intent(context, GeofenceForegroundService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "demoteToBackground failed", e)
            }
        }
    }
}
