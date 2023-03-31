// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
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
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugins.geofencing.pluggables.DisposePluggable
import io.flutter.plugins.geofencing.pluggables.InitPluggable
import org.json.JSONArray

class GeofencingPlugin : ActivityAware, FlutterPlugin, PluginRegistry.NewIntentListener, MethodCallHandler {
  private var mContext : Context? = null
  private var mActivity : Activity? = null
  //private var mGeofencingClient : GeofencingClient? = null

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

    @JvmStatic
    private var channel: MethodChannel? = null

    @JvmStatic
    fun reRegisterAfterReboot(context: Context) {
      synchronized(sGeofenceCacheLock) {
//        var p = context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
//        var persistentGeofences = p.getStringSet(PERSISTENT_GEOFENCES_IDS, null)
//        if (persistentGeofences == null) {
//          return
//        }
//        for (id in persistentGeofences) {
//          val gfJson = p.getString(getPersistentGeofenceKey(id), null)
//          if (gfJson == null) {
//            continue
//          }
//          val gfArgs = JSONArray(gfJson)
//          val list = ArrayList<Object>()
//          for (i in 0 until gfArgs.length()) {
//            list.add(gfArgs.get(i) as Object)
//          }
//          val geoClient = LocationServices.getGeofencingClient(context)
//          registerGeofence(context, geoClient, list, null, false)
//        }
        val args = PreferencesManager.getSettings(context)

        val plugin = GeofencingPlugin()
        plugin.mContext = context

        initializeService(context, args)

        val settings = args[Keys.ARG_SETTINGS] as Map<*, *>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
          context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
          == PackageManager.PERMISSION_GRANTED
        ) {
          startIsolateService(context, settings)
        }
      }
    }

    @JvmStatic
    private fun sendResultWithDelay(context: Context, result: Result?, value: Boolean, delay: Long) {
      context.mainLooper.let {
        Handler(it).postDelayed({
          result?.success(value)
        }, delay)
      }
    }

    @SuppressLint("MissingPermission")
    @JvmStatic
    private fun registerGeofence(context: Context,
                                 geofencingClient: GeofencingClient,
                                 args: ArrayList<*>?,
                                 result: Result?,
                                 cache: Boolean) {
      if (IsolateHolderService.isServiceRunning) {
        // The service is running already
        Log.d(TAG, "Locator service is already running")
        result?.success(true)
        return
      }
      Log.d(TAG, "registerGeofence")

      val callbackHandle = args!![0] as Long
      PreferencesManager.setCallbackHandle(context, Keys.CALLBACK_HANDLE_KEY, callbackHandle)

      // Call InitPluggable with initCallbackHandle
//      (args[Keys.ARG_INIT_CALLBACK] as? Long)?.let { initCallbackHandle ->
//        val initPluggable = InitPluggable()
//        initPluggable.setCallback(context, initCallbackHandle)

//        // Set init data if available
//        (args[Keys.ARG_INIT_DATA_CALLBACK] as? Map<*, *>)?.let { initData ->
//          initPluggable.setInitData(context, initData)
//        }
//      }

      // Call DisposePluggable with disposeCallbackHandle
//      (args[Keys.ARG_DISPOSE_CALLBACK] as? Long)?.let {
//        val disposePluggable = DisposePluggable()
//        disposePluggable.setCallback(context, it)
//      }

      val settings = args

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
              (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                      == PackageManager.PERMISSION_DENIED)) {
        val msg = "'registerGeofence' requires the ACCESS_FINE_LOCATION permission."
        Log.w(TAG, msg)
        result?.error(msg, null, null)
      }


      startIsolateService(context, settings)

      // We need to know when the service binded exactly, there is some delay between starting a
      // service and it's binding
      // HELP WANTED: I couldn't find a better way to handle this, so any help or suggestion would be appreciated
      sendResultWithDelay(context, result, true, 1000)


//      val id = args[1] as String
//      val lat = args[2] as Double
//      val long = args[3] as Double
//      val radius = (args[4] as Number).toFloat()
//      val fenceTriggers = args[5] as Int
//      val initialTriggers = args[6] as Int
//      val expirationDuration = (args[7] as Int).toLong()
//      val loiteringDelay = args[8] as Int
//      val notificationResponsiveness = args[9] as Int
//      val geofence = Geofence.Builder()
//        .setRequestId(id)
//        .setCircularRegion(lat, long, radius)
//        .setTransitionTypes(fenceTriggers)
//        .setLoiteringDelay(loiteringDelay)
//        .setNotificationResponsiveness(notificationResponsiveness)
//        .setExpirationDuration(expirationDuration)
//        .build()
//
//      geofencingClient.addGeofences(getGeofencingRequest(geofence, initialTriggers),
//        getGeofencePendingIndent(context, callbackHandle))?.run {
//        addOnSuccessListener {
//          Log.i(TAG, "Successfully added geofence")
//          if (cache) {
//            addGeofenceToCache(context, id, args)
//          }
//          result?.success(true)
//        }
//        addOnFailureListener {
//          Log.e(TAG, "Failed to add geofence: $it")
//          result?.error(it.toString(), null, null)
//        }
//      }
//
//      val intent = Intent(context, IsolateHolderService::class.java)
//      intent.action = IsolateHolderService.ACTION_START
//      ContextCompat.startForegroundService(context, intent)
    }

    @JvmStatic
    private fun startIsolateService(context: Context, settings: ArrayList<*>) {
      Log.e("BackgroundLocatorPlugin", "startIsolateService")
      val intent = Intent(context, IsolateHolderService::class.java)
      intent.action = IsolateHolderService.ACTION_START
      intent.putExtra(Keys.SETTINGS_GEOFENCE_REQUEST_ID,
        settings[1] as? String)
      intent.putExtra(Keys.SETTINGS_GEOFENCE_LATITUDE,
        settings[2] as? Double)
      intent.putExtra(Keys.SETTINGS_GEOFENCE_LONGITUDE,
        settings[3] as? Double)
      intent.putExtra(Keys.SETTINGS_GEOFENCE_RADIUS,
        settings[4] as? Float)
      intent.putExtra(Keys.SETTINGS_GEOFENCE_FENCETRIGGES,
        settings[5] as? Int)
      intent.putExtra(Keys.SETTINGS_GEOFENCE_INITIALTRIGGERS,
        settings[6] as? Int)
      intent.putExtra(Keys.SETTINGS_GEOFENCE_EXPIRATIONDURATION, settings[7] as? Long)
      intent.putExtra(Keys.SETTINGS_GEOFENCE_LOITERINGDELAY, settings[8] as? Int)
      intent.putExtra(Keys.SETTINGS_GEOFENCE_NOTIFICATIONRESPONSIVENESS, settings[9] as? Int)

      if (PreferencesManager.getCallbackHandle(context, Keys.INIT_CALLBACK_HANDLE_KEY) != null) {
        intent.putExtra(Keys.SETTINGS_INIT_PLUGGABLE, true)
      }
      if (PreferencesManager.getCallbackHandle(context, Keys.DISPOSE_CALLBACK_HANDLE_KEY) != null) {
        intent.putExtra(Keys.SETTINGS_DISPOSABLE_PLUGGABLE, true)
      }

      ContextCompat.startForegroundService(context, intent)
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
    }

    @JvmStatic
    private fun getGeofencingRequest(geofence: Geofence, initialTrigger: Int): GeofencingRequest {
      return GeofencingRequest.Builder().apply {
        setInitialTrigger(initialTrigger)
        addGeofence(geofence)
      }.build()
    }

    @JvmStatic
    private fun getGeofencePendingIndent(context: Context, callbackHandle: Long): PendingIntent {
      val intent = Intent(context, GeofencingBroadcastReceiver::class.java)
              .putExtra(CALLBACK_HANDLE_KEY, callbackHandle)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
      } else {
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
      }
    }

    @JvmStatic
    private fun removeGeofence(context: Context,
                               geofencingClient: GeofencingClient,
                               args: ArrayList<*>?,
                               result: Result) {
      val ids = listOf(args!![0] as String)
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
    private fun isServiceRunning(result: Result?) {
      result?.success(IsolateHolderService.isServiceRunning)
    }
  }

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
  }

  private fun onAttachedToEngine(context: Context, messenger: BinaryMessenger) {
    val plugin = GeofencingPlugin()
    plugin.mContext = context

    channel = MethodChannel(messenger, Keys.CHANNEL_ID)
    channel?.setMethodCallHandler(plugin)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    //mContext = null
    //mGeofencingClient = null
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    mActivity = binding.getActivity()
    binding.addOnNewIntentListener(this)
  }

  override fun onDetachedFromActivity() {
    //mActivity = null
  }

  override fun onDetachedFromActivityForConfigChanges() {
    //mActivity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    //mActivity = binding.getActivity()
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    val args = call.arguments<ArrayList<*>>()
    when(call.method) {
      "GeofencingPlugin.initializeService" -> {
        // save callback dispatcher to use it when device reboots
        PreferencesManager.saveCallbackDispatcher(mContext!! , args!!)
        initializeService(mContext!!, args)
        result.success(true)
      }
      "GeofencingPlugin.registerGeofence" -> {
        // save setting to use it when device reboots
        PreferencesManager.saveSettings(mContext!!, args!!)
        registerGeofence(
          mContext!!,
          mGeofencingClient!!,
          args,
          result,
          true
        )
      }
      "GeofencingPlugin.removeGeofence" -> removeGeofence(mContext!!,
              mGeofencingClient!!,
              args,
              result)
      "GeofencingPlugin.getRegisteredGeofenceIds" -> getRegisteredGeofenceIds(mContext!!, result)
      Keys.METHOD_PLUGIN_IS_REGISTER_LOCATION_UPDATE -> isServiceRunning(result)
      Keys.METHOD_PLUGIN_IS_SERVICE_RUNNING -> isServiceRunning(result)
      else -> result.notImplemented()
    }
  }

  override fun onNewIntent(intent: Intent): Boolean {
    if (intent.action != Keys.NOTIFICATION_ACTION) {
      // this is not our notification
      return false
    }

    IsolateHolderService.getBinaryMessenger(mContext)?.let { binaryMessenger ->
      val notificationCallback =
        PreferencesManager.getCallbackHandle(
          mActivity!!,
          Keys.NOTIFICATION_CALLBACK_HANDLE_KEY
        )
      if (notificationCallback != null && IsolateHolderService.backgroundEngine != null) {
        val backgroundChannel =
          MethodChannel(
            binaryMessenger,
            Keys.BACKGROUND_CHANNEL_ID
          )
        mActivity?.mainLooper?.let {
          Handler(it)
            .post {
              backgroundChannel.invokeMethod(
                Keys.BCM_NOTIFICATION_CLICK,
                hashMapOf(Keys.ARG_NOTIFICATION_CALLBACK to notificationCallback)
              )
            }
        }
      }
    }

    return true
  }
}