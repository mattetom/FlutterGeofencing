// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.MethodCall
import org.json.JSONArray

class GeofencingPlugin : ActivityAware, FlutterPlugin, MethodCallHandler {
  private var mContext : Context? = null
  private var mActivity : Activity? = null
  private var mGeofencingClient : GeofencingClient? = null

  companion object {
    @JvmStatic
    private val TAG = "GeofencingPlugin"
    @JvmStatic
    val SHARED_PREFERENCES_KEY = "geofencing_plugin_cache"
    @JvmStatic
    val CALLBACK_HANDLE_KEY = "callback_handle"
    @JvmStatic
    val CALLBACK_DISPATCHER_HANDLE_KEY = "callback_dispatch_handler"
    @JvmStatic
    val PERSISTENT_GEOFENCES_KEY = "persistent_geofences"
    @JvmStatic
    val PERSISTENT_GEOFENCES_IDS = "persistent_geofences_ids"
    @JvmStatic
    private val sGeofenceCacheLock = Object()

    // Method channel back to the host app's main isolate. Used to emit
    // diagnostic events (registration recovered/failed after retry, etc.).
    @JvmStatic
    private var sMainChannel: MethodChannel? = null

    // Exponential backoff schedule for transient registration failures
    // (e.g. GEOFENCE_NOT_AVAILABLE when location services are momentarily off).
    @JvmStatic
    private val sRetryDelaysMs = longArrayOf(2_000L, 4_000L, 8_000L, 30_000L)

    @JvmStatic
    private val sRetryHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    private val sPendingRetries = HashMap<String, Runnable>()

    @JvmStatic
    private fun isTransientGeofenceError(code: Int): Boolean = when (code) {
      com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> true
      // CommonStatusCodes that can self-heal: GMS service not yet connected,
      // a network blip, or an internal error inside Play Services. We don't
      // include DEVELOPER_ERROR (10) — that almost always means the package
      // is mis-registered or a permission/parameter problem the OS won't
      // forgive on retry.
      com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR -> true
      com.google.android.gms.common.api.CommonStatusCodes.API_NOT_CONNECTED -> true
      com.google.android.gms.common.api.CommonStatusCodes.INTERNAL_ERROR -> true
      else -> false
    }

    @JvmStatic
    fun emitDiagnostic(event: String, payload: Map<String, Any?>) {
      val channel = sMainChannel ?: return
      Handler(Looper.getMainLooper()).post {
        try {
          channel.invokeMethod("GeofencingPlugin.$event", payload)
        } catch (t: Throwable) {
          Log.w(TAG, "Failed to emit diagnostic '$event': ${t.message}")
        }
      }
    }

    @JvmStatic
    private fun cancelPendingRetry(id: String) {
      synchronized(sPendingRetries) {
        val pending = sPendingRetries.remove(id)
        if (pending != null) {
          sRetryHandler.removeCallbacks(pending)
        }
      }
    }

    @JvmStatic
    private fun scheduleRetry(
      context: Context,
      geofencingClient: GeofencingClient,
      args: ArrayList<*>,
      nextAttempt: Int
    ) {
      val id = args[1] as String
      if (nextAttempt >= sRetryDelaysMs.size) {
        Log.w(TAG, "Geofence '$id' registration retries exhausted")
        synchronized(sPendingRetries) { sPendingRetries.remove(id) }
        emitDiagnostic(
          "registrationFailedFinal",
          mapOf("id" to id, "attempts" to nextAttempt)
        )
        return
      }
      val delay = sRetryDelaysMs[nextAttempt]
      val runnable = Runnable {
        synchronized(sPendingRetries) { sPendingRetries.remove(id) }
        retryRegisterGeofence(context, geofencingClient, args, nextAttempt)
      }
      synchronized(sPendingRetries) {
        val existing = sPendingRetries[id]
        if (existing != null) {
          sRetryHandler.removeCallbacks(existing)
        }
        sPendingRetries[id] = runnable
      }
      Log.i(TAG, "Scheduling geofence '$id' retry #${nextAttempt + 1} in ${delay}ms")
      sRetryHandler.postDelayed(runnable, delay)
    }

    @JvmStatic
    private fun retryRegisterGeofence(
      context: Context,
      geofencingClient: GeofencingClient,
      args: ArrayList<*>,
      attempt: Int
    ) {
      val id = args[1] as String
      val callbackHandle = args[0] as Long
      val lat = args[2] as Double
      val long = args[3] as Double
      val radius = (args[4] as Number).toFloat()
      val fenceTriggers = args[5] as Int
      val initialTriggers = args[6] as Int
      val expirationDuration = (args[7] as Int).toLong()
      val loiteringDelay = args[8] as Int
      val notificationResponsiveness = args[9] as Int
      val geofenceBuilder = Geofence.Builder()
        .setRequestId(id)
        .setCircularRegion(lat, long, radius)
        .setTransitionTypes(fenceTriggers)
        .setNotificationResponsiveness(notificationResponsiveness)
        .setExpirationDuration(expirationDuration)
      // setLoiteringDelay is only honored when DWELL is among the transitions.
      if ((fenceTriggers and Geofence.GEOFENCE_TRANSITION_DWELL) != 0) {
        geofenceBuilder.setLoiteringDelay(loiteringDelay)
      }
      val geofence = geofenceBuilder.build()
      geofencingClient.addGeofences(
        getGeofencingRequest(geofence, initialTriggers),
        getGeofencePendingIndent(context, callbackHandle, id)
      )?.run {
        addOnSuccessListener {
          Log.i(TAG, "Geofence '$id' retry succeeded on attempt ${attempt + 1}")
          addGeofenceToCache(context, id, args)
          emitDiagnostic(
            "registrationRecovered",
            mapOf("id" to id, "attempts" to attempt + 1)
          )
        }
        addOnFailureListener { exception ->
          val code = (exception as? com.google.android.gms.common.api.ApiException)?.statusCode ?: -1
          Log.w(TAG, "Geofence '$id' retry #${attempt + 1} failed (code=$code)")
          if (isTransientGeofenceError(code)) {
            scheduleRetry(context, geofencingClient, args, attempt + 1)
          } else {
            emitDiagnostic(
              "registrationFailedFinal",
              mapOf("id" to id, "code" to code, "attempts" to attempt + 1)
            )
          }
        }
      }
    }

    @JvmStatic
    fun reRegisterAfterReboot(context: Context) {
      synchronized(sGeofenceCacheLock) {
        var p = context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        var persistentGeofences = p.getStringSet(PERSISTENT_GEOFENCES_IDS, null)
        if (persistentGeofences == null) {
          return
        }
        for (id in persistentGeofences) {
          val gfJson = p.getString(getPersistentGeofenceKey(id), null)
          if (gfJson == null) {
            continue
          }
          val gfArgs = JSONArray(gfJson)
          val list = ArrayList<Object>()
          for (i in 0 until gfArgs.length()) {
            list.add(gfArgs.get(i) as Object)
          }
          val geoClient = LocationServices.getGeofencingClient(context)
          Log.i(TAG, "reRegisterAfterReboot")
          registerGeofence(context, geoClient, list, null, false)
        }
      }
    }

    @JvmStatic
    private fun registerGeofence(context: Context,
                                 geofencingClient: GeofencingClient,
                                 args: ArrayList<*>?,
                                 result: Result?,
                                 cache: Boolean) {
      val callbackHandle = args!![0] as Long
      val id = args[1] as String
      val lat = args[2] as Double
      val long = args[3] as Double
      val radius = (args[4] as Number).toFloat()
      val fenceTriggers = args[5] as Int
      val initialTriggers = args[6] as Int
      val expirationDuration = (args[7] as Int).toLong()
      val loiteringDelay = args[8] as Int
      val notificationResponsiveness = args[9] as Int
      val geofenceBuilder = Geofence.Builder()
              .setRequestId(id)
              .setCircularRegion(lat, long, radius)
              .setTransitionTypes(fenceTriggers)
              .setNotificationResponsiveness(notificationResponsiveness)
              .setExpirationDuration(expirationDuration)
      // setLoiteringDelay is only honored when DWELL is among the transitions.
      if ((fenceTriggers and Geofence.GEOFENCE_TRANSITION_DWELL) != 0) {
        geofenceBuilder.setLoiteringDelay(loiteringDelay)
      }
      val geofence = geofenceBuilder.build()
      // Verify runtime permissions BEFORE calling addGeofences, otherwise
      // Play Services responds with the opaque DEVELOPER_ERROR (code 10).
      // ACCESS_FINE_LOCATION is mandatory on every API level; on Android 10+
      // (API 29) ACCESS_BACKGROUND_LOCATION is required for background
      // geofence delivery — without it Play Services rejects the request
      // even when the app is in the foreground at registration time.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
              context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                      == PackageManager.PERMISSION_DENIED) {
        val msg = "'registerGeofence' requires the ACCESS_FINE_LOCATION permission."
        Log.w(TAG, msg)
        result?.error(
          "PERMISSION_DENIED_FINE_LOCATION",
          msg,
          mapOf("id" to id, "permission" to Manifest.permission.ACCESS_FINE_LOCATION),
        )
        return
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
              context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                      == PackageManager.PERMISSION_DENIED) {
        val msg = "'registerGeofence' requires ACCESS_BACKGROUND_LOCATION on Android 10+. " +
          "The user must select 'Allow all the time' in the location permission settings."
        Log.w(TAG, msg)
        result?.error(
          "PERMISSION_DENIED_BACKGROUND_LOCATION",
          msg,
          mapOf("id" to id, "permission" to Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )
        return
      }
      // If another registration for this id was retrying in the background,
      // cancel it — this new call supersedes the pending work.
      cancelPendingRetry(id)

      geofencingClient.addGeofences(getGeofencingRequest(geofence, initialTriggers),
              getGeofencePendingIndent(context, callbackHandle, id))?.run {
        addOnSuccessListener {
          Log.i(TAG, "Successfully added geofence: $id")
          if (cache) {
            addGeofenceToCache(context, id, args)
          }
          result?.success(true)
        }
        addOnFailureListener { exception ->
          val errorCode = when (exception) {
            is com.google.android.gms.common.api.ApiException -> exception.statusCode
            else -> -1
          }
          val errorMessage = getGeofenceErrorMessage(errorCode)
          Log.e(TAG, "Failed to add geofence '$id': $errorMessage (code: $errorCode)")
          result?.error("GEOFENCE_ERROR", errorMessage, mapOf("code" to errorCode, "id" to id))

          // On transient failures (location services off, no data connectivity)
          // retry with exponential backoff so we can recover silently once the
          // underlying condition clears. Non-transient failures (permissions,
          // too many geofences) are surfaced to the caller and left alone.
          if (cache && isTransientGeofenceError(errorCode)) {
            @Suppress("UNCHECKED_CAST")
            scheduleRetry(context, geofencingClient, args as ArrayList<*>, 0)
          }
        }
      }
    }

    @JvmStatic
    private fun addGeofenceToCache(context: Context, id: String, args: ArrayList<*>) {
      synchronized(sGeofenceCacheLock) {
        var p = context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        var obj = JSONArray(args)
        var persistentGeofences = p.getStringSet(PERSISTENT_GEOFENCES_IDS, null)
        if (persistentGeofences == null) {
          persistentGeofences = HashSet<String>()
        } else {
          persistentGeofences = HashSet<String>(persistentGeofences)
        }
        persistentGeofences.add(id)
        context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PERSISTENT_GEOFENCES_IDS, persistentGeofences)
                .putString(getPersistentGeofenceKey(id), obj.toString())
                .apply()
      }
    }


    @JvmStatic
    private fun initializeService(context: Context, args: ArrayList<*>?) {
      Log.d(TAG, "Initializing GeofencingService")
      val callbackHandle = args!![0] as Long
      context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
              .edit()
              .putLong(CALLBACK_DISPATCHER_HANDLE_KEY, callbackHandle)
              .apply()

        // Create channel for notifications
        createChannel(context)
    }

    private val NOTIFICATION_ID = 66
    private val CHANNEL_ID = "GeofencePluginChannel"

    @JvmStatic
    private fun createChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationChannel = NotificationChannel(
            CHANNEL_ID,
            "AndroidGeofencingPlugin",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
            enableLights(true)
            enableVibration(true)
            lightColor = Color.RED
            description = "Android plugin for geofencing"
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(notificationChannel)
    }
}

    @JvmStatic
    private fun getGeofencingRequest(geofence: Geofence, initialTrigger: Int): GeofencingRequest {
      return GeofencingRequest.Builder().apply {
        setInitialTrigger(initialTrigger)
        addGeofence(geofence)
      }.build()
    }

    @JvmStatic
    private fun getGeofencePendingIndent(context: Context, callbackHandle: Long, geofenceId: String): PendingIntent {
      val intent = Intent(context, GeofencingBroadcastReceiver::class.java)
              .putExtra(CALLBACK_HANDLE_KEY, callbackHandle)
              .putExtra("geofence_id", geofenceId)
      // Use geofence ID hash as requestCode to ensure each geofence gets a unique PendingIntent
      val requestCode = geofenceId.hashCode()
      // Geofencing PendingIntents MUST be mutable: Play Services writes the
      // transition type, triggering geofences and triggering location into
      // the broadcast Intent extras when an event fires (see
      // GeofencingEvent.fromIntent). Marking the intent FLAG_IMMUTABLE
      // causes addGeofences to reject the registration with
      // CommonStatusCodes.DEVELOPER_ERROR (10) on Android 12+.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
      } else {
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT)
      }
    }

    @JvmStatic
    private fun removeGeofence(context: Context,
                               geofencingClient: GeofencingClient,
                               args: ArrayList<*>?,
                               result: Result) {
      val ids = listOf(args!![0] as String)
      // Cancel any in-flight retry for these ids so they don't re-register
      // the geofence after the caller explicitly asked to remove it.
      for (id in ids) {
        cancelPendingRetry(id)
      }
      geofencingClient.removeGeofences(ids).run {
        addOnSuccessListener {
          for (id in ids) {
            removeGeofenceFromCache(context, id)
          }
          result.success(true)
        }
        addOnFailureListener {
          result.error(it.toString(), null, null)
        }
      }
    }

    @JvmStatic
    private fun getRegisteredGeofenceIds(context: Context, result: Result) {
      synchronized(sGeofenceCacheLock) {
        val list = ArrayList<String>()
        var p = context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        var persistentGeofences = p.getStringSet(PERSISTENT_GEOFENCES_IDS, null)
        if (persistentGeofences != null && persistentGeofences.size > 0) {
          for (id in persistentGeofences) {
            list.add(id)
          }
        }
        result.success(list)
      }
    }

    @JvmStatic
    private fun removeGeofenceFromCache(context: Context, id: String) {
      synchronized(sGeofenceCacheLock) {
        var p = context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        var persistentGeofences = p.getStringSet(PERSISTENT_GEOFENCES_IDS, null)
        if (persistentGeofences == null) {
          return
        }
        persistentGeofences = HashSet<String>(persistentGeofences)
        persistentGeofences.remove(id)
        p.edit()
                .remove(getPersistentGeofenceKey(id))
                .putStringSet(PERSISTENT_GEOFENCES_IDS, persistentGeofences)
                .apply()
      }
    }

    @JvmStatic
    private fun getPersistentGeofenceKey(id: String): String {
      return "persistent_geofence/" + id
    }

    @JvmStatic
    private fun getGeofenceErrorMessage(errorCode: Int): String {
      return when (errorCode) {
        // Geofencing-specific status codes (1000+).
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
          "Geofence service is not available now. Typically this is because the device has no data connection."
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES ->
          "Too many geofences registered. Android supports max 100 geofences per app."
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS ->
          "Too many pending intents registered."
        com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
          "Insufficient location permissions. Ensure ACCESS_FINE_LOCATION and ACCESS_BACKGROUND_LOCATION are granted (request 'Allow all the time' on Android 10+)."
        // CommonStatusCodes (1..17). Play Services surfaces these on
        // addGeofences when the request can't even reach the geofencing
        // backend — most often emulator/Play Services issues, missing
        // background permission, or a misregistered app package.
        com.google.android.gms.common.api.CommonStatusCodes.DEVELOPER_ERROR ->
          "Developer error from Play Services (code: 10). Common causes: " +
            "ACCESS_BACKGROUND_LOCATION not granted at runtime; emulator " +
            "Google Play Services package cache out of sync (try uninstalling " +
            "the app and clearing GMS storage, or test on a physical device); " +
            "geofence request parameters rejected by GMS."
        com.google.android.gms.common.api.CommonStatusCodes.API_NOT_CONNECTED ->
          "Google Play Services not connected yet (code: 17). Will retry."
        com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR ->
          "Network error from Play Services (code: 7). Will retry."
        com.google.android.gms.common.api.CommonStatusCodes.INTERNAL_ERROR ->
          "Internal Play Services error (code: 8). Will retry."
        com.google.android.gms.common.api.CommonStatusCodes.RESOLUTION_REQUIRED ->
          "Play Services requires a resolution (code: 6) — typically location services off or resolvable account issue."
        com.google.android.gms.common.api.CommonStatusCodes.SIGN_IN_REQUIRED ->
          "Google account sign-in required (code: 4) for geofencing."
        else -> "Unknown geofence error (code: $errorCode)"
      }
    }
  }

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    mContext = binding.getApplicationContext()
    mGeofencingClient = LocationServices.getGeofencingClient(mContext!!)
    val channel = MethodChannel(binding.getBinaryMessenger(), "plugins.flutter.io/geofencing_plugin")
    channel.setMethodCallHandler(this)
    // Keep a static reference so the retry/diagnostic helpers in the
    // companion object can post events back to the main isolate.
    sMainChannel = channel
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    if (sMainChannel != null) {
      sMainChannel = null
    }
    mContext = null
    mGeofencingClient = null
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    mActivity = binding.getActivity()
  }

  override fun onDetachedFromActivity() {
    mActivity = null
  }

  override fun onDetachedFromActivityForConfigChanges() {
    mActivity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    mActivity = binding.getActivity()
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    val args = call.arguments<ArrayList<*>>()
    when(call.method) {
      "GeofencingPlugin.initializeService" -> {
        initializeService(mContext!!, args)
        result.success(true)
      }
      "GeofencingPlugin.registerGeofence" -> registerGeofence(mContext!!,
              mGeofencingClient!!,
              args,
              result,
              true)
      "GeofencingPlugin.removeGeofence" -> removeGeofence(mContext!!,
              mGeofencingClient!!,
              args,
              result)
      "GeofencingPlugin.getRegisteredGeofenceIds" -> getRegisteredGeofenceIds(mContext!!, result)
      "GeofencingPlugin.resetBackgroundEngine" -> {
        GeofencingService.resetBackgroundEngine()
        result.success(true)
      }
      else -> result.notImplemented()
    }
  }
}