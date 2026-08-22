package com.matteotomasini.flutter_geofence_plugin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persistenza su SharedPreferences: handle dei callback Dart (servono al
 * receiver per avviare l'engine headless ad app terminata) e regioni
 * registrate (per la ri-registrazione dopo il reboot).
 */
internal object GeofencePersistence {
    private const val PREFS = "flutter_geofence_plugin"
    private const val KEY_DISPATCHER = "dispatcher_handle"
    private const val KEY_CALLBACK = "callback_handle"
    private const val KEY_REGION_PREFIX = "region_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setHandles(context: Context, dispatcher: Long, callback: Long) {
        prefs(context).edit()
            .putLong(KEY_DISPATCHER, dispatcher)
            .putLong(KEY_CALLBACK, callback)
            .apply()
    }

    fun getDispatcherHandle(context: Context): Long =
        prefs(context).getLong(KEY_DISPATCHER, 0L)

    fun getCallbackHandle(context: Context): Long =
        prefs(context).getLong(KEY_CALLBACK, 0L)

    fun saveRegion(context: Context, region: RegionData) {
        prefs(context).edit()
            .putString(KEY_REGION_PREFIX + region.id, region.toJson())
            .apply()
    }

    fun removeRegion(context: Context, id: String) {
        prefs(context).edit().remove(KEY_REGION_PREFIX + id).apply()
    }

    private const val KEY_SELFHEAL_BOOT = "selfheal_boot_epoch"

    /** Il self-heal gira al più una volta per boot: ri-registrare a ogni
     *  apertura dell'app azzererebbe lo stato delle fence in Play Services,
     *  producendo finti enter/exit di ri-sincronizzazione al fix successivo. */
    fun wasSelfHealDoneForBoot(context: Context, bootEpochMs: Long): Boolean {
        val stored = prefs(context).getLong(KEY_SELFHEAL_BOOT, Long.MIN_VALUE)
        return kotlin.math.abs(stored - bootEpochMs) < 5_000L
    }

    fun markSelfHealDoneForBoot(context: Context, bootEpochMs: Long) {
        prefs(context).edit().putLong(KEY_SELFHEAL_BOOT, bootEpochMs).apply()
    }

    fun getRegion(context: Context, id: String): RegionData? =
        prefs(context).getString(KEY_REGION_PREFIX + id, null)
            ?.let { RegionData.fromJson(it) }

    fun getRegions(context: Context): List<RegionData> =
        prefs(context).all
            .filterKeys { it.startsWith(KEY_REGION_PREFIX) }
            .values
            .mapNotNull { it as? String }
            .map { RegionData.fromJson(it) }
}

internal data class RegionData(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    /** Bitmask: 1 = enter, 2 = exit (stessi valori delle costanti Geofence). */
    val triggers: Int,
    /** Bitmask degli initial trigger sintetici alla registrazione. */
    val initialTriggers: Int = 1,
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("radius", radius)
        .put("triggers", triggers)
        .put("initialTriggers", initialTriggers)
        .toString()

    companion object {
        fun fromJson(json: String): RegionData {
            val o = JSONObject(json)
            return RegionData(
                id = o.getString("id"),
                latitude = o.getDouble("latitude"),
                longitude = o.getDouble("longitude"),
                radius = o.getDouble("radius"),
                triggers = o.getInt("triggers"),
                initialTriggers = o.optInt("initialTriggers", 1),
            )
        }

        fun fromCallArguments(args: Map<*, *>): RegionData = RegionData(
            id = args["id"] as String,
            latitude = (args["latitude"] as Number).toDouble(),
            longitude = (args["longitude"] as Number).toDouble(),
            radius = (args["radius"] as Number).toDouble(),
            triggers = (args["triggers"] as Number).toInt(),
            initialTriggers = (args["initialTriggers"] as? Number)?.toInt() ?: 1,
        )
    }
}
