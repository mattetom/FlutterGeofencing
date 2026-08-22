package com.matteotomasini.flutter_geofence_plugin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/** Entry point Android del plugin: gestisce il MethodChannel principale. */
class FlutterGeofencePlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var context: Context
    private var channel: MethodChannel? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPermissionResult: Result? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initialize" -> {
                val args = call.arguments as Map<*, *>
                GeofencePersistence.setHandles(
                    context,
                    (args["dispatcherHandle"] as Number).toLong(),
                    (args["callbackHandle"] as Number).toLong(),
                )
                selfHealRegistrations()
                result.success(null)
            }

            "requestPermissions" -> requestPermissions(result)

            "registerGeofence" -> {
                if (GeofencePersistence.getDispatcherHandle(context) == 0L) {
                    result.error(
                        "not_initialized",
                        "Call FlutterGeofencePlugin.initialize() before registering geofences.",
                        null,
                    )
                    return
                }
                if (!hasFineLocation()) {
                    result.error(
                        "no_permission",
                        "ACCESS_FINE_LOCATION not granted. Call requestPermissions() first.",
                        null,
                    )
                    return
                }
                val region = RegionData.fromCallArguments(call.arguments as Map<*, *>)
                GeofenceRegistrar.addGeofence(
                    context,
                    region,
                    onSuccess = {
                        GeofencePersistence.saveRegion(context, region)
                        result.success(null)
                    },
                    onFailure = { e ->
                        result.error("register_failed", e.message, null)
                    },
                )
            }

            "removeGeofence" -> {
                val id = (call.arguments as Map<*, *>)["id"] as String
                GeofenceRegistrar.removeGeofence(context, id) {
                    GeofencePersistence.removeRegion(context, id)
                    result.success(null)
                }
            }

            "getRegisteredGeofenceIds" -> {
                result.success(GeofencePersistence.getRegions(context).map { it.id })
            }

            else -> result.notImplemented()
        }
    }

    /// Play Services può perdere le geofence mentre la cache locale le
    /// considera registrate (reboot col receiver soppresso o crashato,
    /// storage clear di GMS): a ogni initialize() — quindi a ogni avvio
    /// dell'app — le regioni salvate vengono ri-registrate in modo
    /// idempotente, SENZA initial trigger (nessun evento sintetico a ogni
    /// apertura; il recovery col sintetico resta al RebootBroadcastReceiver).
    private fun selfHealRegistrations() {
        if (!hasFineLocation()) return
        // Una volta per boot: ogni ri-registrazione azzera lo stato della
        // fence in Play Services e il fix successivo consegnerebbe un finto
        // enter/exit di ri-sincronizzazione (osservato sul campo il 21/08).
        val bootEpoch =
            System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        if (GeofencePersistence.wasSelfHealDoneForBoot(context, bootEpoch)) return
        GeofencePersistence.markSelfHealDoneForBoot(context, bootEpoch)
        for (region in GeofencePersistence.getRegions(context)) {
            GeofenceRegistrar.addGeofence(
                context,
                region.copy(initialTriggers = 0),
                onSuccess = { Log.i(TAG, "Self-heal re-registered ${region.id}") },
                onFailure = { e ->
                    Log.w(TAG, "Self-heal re-registration failed for ${region.id}", e)
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // Permessi: su Android 11+ ACCESS_BACKGROUND_LOCATION va richiesto in
    // un secondo passo, dopo che il permesso foreground è stato concesso
    // (il sistema porta l'utente nelle impostazioni per "Consenti sempre").
    // ------------------------------------------------------------------

    private fun requestPermissions(result: Result) {
        val activity = activityBinding?.activity
        if (activity == null) {
            result.error(
                "no_activity",
                "requestPermissions() requires a foreground activity.",
                null,
            )
            return
        }
        if (hasBackgroundLocation()) {
            result.success(true)
            return
        }
        if (pendingPermissionResult != null) {
            result.error("in_progress", "A permission request is already in progress.", null)
            return
        }
        pendingPermissionResult = result
        if (!hasFineLocation()) {
            activity.requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_FINE,
            )
        } else {
            requestBackgroundLocation(activity)
        }
    }

    private fun requestBackgroundLocation(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_BACKGROUND,
            )
        } else {
            // Prima di Android 10 il permesso foreground copre anche il background.
            finishPermissionRequest(hasFineLocation())
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        when (requestCode) {
            REQUEST_FINE -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                val activity = activityBinding?.activity
                if (granted && activity != null) {
                    requestBackgroundLocation(activity)
                } else {
                    finishPermissionRequest(false)
                }
                return true
            }

            REQUEST_BACKGROUND -> {
                finishPermissionRequest(hasBackgroundLocation())
                return true
            }
        }
        return false
    }

    private fun finishPermissionRequest(granted: Boolean) {
        pendingPermissionResult?.success(granted)
        pendingPermissionResult = null
    }

    private fun hasFineLocation(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasFineLocation()
        return context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------
    // ActivityAware
    // ------------------------------------------------------------------

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    override fun onDetachedFromActivity() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
    }

    private companion object {
        const val TAG = "FlutterGeofencePlugin"
        const val METHOD_CHANNEL = "com.matteotomasini.flutter_geofence_plugin/methods"
        const val REQUEST_FINE = 3470
        const val REQUEST_BACKGROUND = 3471
    }
}
