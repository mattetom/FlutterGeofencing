package com.matteotomasini.flutter_geofence_plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 *
 * Overlapping callbacks (several geofences in one delivery, or events a few
 * seconds apart): every promote is counted and the stop is sent only when the
 * last callback has called demote. The stop also uses stopSelfResult(startId):
 * if another startForegroundService was requested after the stop, the service
 * keeps running. A plain stopSelf() used to stop the service while a
 * startForegroundService was still queued; that request never got its
 * startForeground and Android killed the process with
 * ForegroundServiceDidNotStartInTimeException.
 */
class GeofenceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stop only if this is the latest request: a startForegroundService
            // that came after it keeps the service alive and will get its
            // startForeground.
            if (stopSelfResult(startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                Log.i(TAG, "Stop $startId skipped: a newer start is pending")
            }
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

        /**
         * Callbacks currently between promote and demote. Counted even when
         * the OS rejects the start: the callback still calls demote, so the
         * count stays in step with the Dart calls.
         */
        private var activePromotions = 0

        /** A callback that dies without demote does not hold the service forever. */
        private const val MAX_FOREGROUND_MS = 5 * 60 * 1000L
        private val mainHandler = Handler(Looper.getMainLooper())
        private var forceStop: Runnable? = null

        @Synchronized
        fun promote(context: Context) {
            activePromotions++
            scheduleForceStop(context.applicationContext)
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

        @Synchronized
        fun demote(context: Context) {
            if (activePromotions > 0) activePromotions--
            if (activePromotions > 0) {
                Log.i(TAG, "demote: $activePromotions callback(s) still running, keep foreground")
                return
            }
            cancelForceStop()
            sendStop(context)
        }

        private fun sendStop(context: Context) {
            val intent = Intent(context, GeofenceForegroundService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "demoteToBackground failed", e)
            }
        }

        private fun scheduleForceStop(appContext: Context) {
            cancelForceStop()
            val r = Runnable {
                synchronized(this) {
                    if (activePromotions == 0) return@Runnable
                    Log.w(TAG, "No demote for $activePromotions callback(s) after ${MAX_FOREGROUND_MS} ms, stopping")
                    activePromotions = 0
                    forceStop = null
                }
                sendStop(appContext)
            }
            forceStop = r
            mainHandler.postDelayed(r, MAX_FOREGROUND_MS)
        }

        private fun cancelForceStop() {
            forceStop?.let { mainHandler.removeCallbacks(it) }
            forceStop = null
        }
    }
}
