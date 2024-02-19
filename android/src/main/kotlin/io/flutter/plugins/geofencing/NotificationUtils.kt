package io.flutter.plugins.geofencing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat


/*
* Creating a NotificationChannel before sending a notification
*/
fun createChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationChannel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
            enableLights(true)
            enableVibration(true)
            lightColor = Color.RED
            description = context.getString(R.string.notification_channel_description)
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(notificationChannel)
    }
}

/*
 * Extension function to send geofence entered notifications
 */
fun NotificationManager.sendGeofenceEnteredNotification(context: Context, foundIndex: Int) {
    val contentIntent = Intent(context, GeofencingPlugin::class.java)
    contentIntent.putExtra(GeofencingConstants.EXTRA_GEOFENCE_INDEX, foundIndex)
    val contentPendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        contentIntent,
        PendingIntent.FLAG_MUTABLE
    )
    val mapImage = BitmapFactory.decodeResource(
        context.resources,
        R.drawable.android_map
    )
    val bigPictureStyle = NotificationCompat.BigPictureStyle()
        .bigPicture(mapImage)

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(
            context.getString(
                R.string.content_text,
                context.getString(GeofencingConstants.LANDMARK_DATA[foundIndex].name)
            )
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(contentPendingIntent)
        .setStyle(bigPictureStyle)
        .setLargeIcon(mapImage)
        .setSmallIcon(R.drawable.android_map)

    notify(NOTIFICATION_ID, builder.build())
}

fun NotificationManager.sendGeofenceExitedNotification(context: Context, foundIndex: Int) {
    val contentIntent = Intent(context, GeofencingPlugin::class.java)
    contentIntent.putExtra(GeofencingConstants.EXTRA_GEOFENCE_INDEX, foundIndex)
    val contentPendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        contentIntent,
        PendingIntent.FLAG_MUTABLE
    )
    val mapImage = BitmapFactory.decodeResource(
        context.resources,
        R.drawable.android_map
    )
    val bigPictureStyle = NotificationCompat.BigPictureStyle()
        .bigPicture(mapImage)

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(
            context.getString(
                R.string.content_exit_text,
                context.getString(GeofencingConstants.LANDMARK_DATA[foundIndex].name)
            )
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(contentPendingIntent)
        .setStyle(bigPictureStyle)
        .setLargeIcon(mapImage)
        .setSmallIcon(R.drawable.android_map)

    notify(NOTIFICATION_ID, builder.build())
}

private val NOTIFICATION_ID = 66
private val CHANNEL_ID = "GeofencePluginChannel"