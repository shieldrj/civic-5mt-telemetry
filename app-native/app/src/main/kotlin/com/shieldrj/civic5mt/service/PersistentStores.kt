package com.shieldrj.civic5mt.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.shieldrj.civic5mt.R
import com.shieldrj.civic5mt.core.DegradationBreakdown
import com.shieldrj.civic5mt.core.FuelBlendId
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LifetimeStore
import com.shieldrj.civic5mt.core.OilConditionGrade
import com.shieldrj.civic5mt.core.OilLifeProfile
import com.shieldrj.civic5mt.core.OilProfileStore
import org.json.JSONObject

/**
 * Where the two records that have to survive a restart are kept.
 *
 * Deliberately not Room. These are two single rows, read once at startup and written at most
 * every thirty seconds; a database for them would be ceremony around a key-value pair. Room
 * earns its place when the trip history lands - queryable drives and time-series logging are
 * what it is actually for.
 *
 * Both are written as JSON rather than as individual preference keys, so a schema change is
 * one parse to reason about, and so the shape on disk matches the shape of the JSON rescued
 * out of the WebView. That symmetry is what makes the migration a straight read.
 */
private const val PREFS_NAME = "civic_telemetry"
private const val KEY_LIFETIME = "civic_2013_lifetime_stats_v2"
private const val KEY_OIL = "civic_2013_oil_profile_v1"
private const val KEY_FUEL_BLEND = "civic_2013_fuel_blend_v1"
private const val KEY_MIGRATION_DONE = "rescued_localstorage_imported_v1"

private const val TAG = "PersistentStores"

fun telemetryPrefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

// ── Lifetime record ──────────────────────────────────────────────────────────────

class PrefsLifetimeStore(context: Context) : LifetimeStore {
    private val prefs = telemetryPrefs(context)

    override fun load(): LifetimeStats? {
        val raw = prefs.getString(KEY_LIFETIME, null) ?: return null
        return runCatching { parseLifetime(JSONObject(raw)) }
            .onFailure { Log.w(TAG, "Lifetime record unreadable, ignoring rather than overwriting", it) }
            .getOrNull()
    }

    override fun save(stats: LifetimeStats) {
        val json = JSONObject()
            .put("totalMiles", stats.totalMiles)
            .put("totalFuelGallons", stats.totalFuelGallons)
            // Stored for compatibility with the WebView shape. It is recomputed on load -
            // miles and gallons are the measurements, the ratio is a view of them.
            .put("lifetimeMpg", stats.lifetimeMpg)
            .put("firstTrackedTimestamp", stats.firstTrackedTimestamp)
        prefs.edit().putString(KEY_LIFETIME, json.toString()).apply()
    }
}

private fun parseLifetime(json: JSONObject): LifetimeStats = LifetimeStats(
    totalMiles = json.optDouble("totalMiles", 0.0).coerceAtLeast(0.0),
    totalFuelGallons = json.optDouble("totalFuelGallons", 0.0).coerceAtLeast(0.0),
    firstTrackedTimestamp = json.optLong("firstTrackedTimestamp", 0L),
)

// ── Oil profile ──────────────────────────────────────────────────────────────────

class PrefsOilProfileStore(context: Context) : OilProfileStore {
    private val prefs = telemetryPrefs(context)

    override fun load(): OilLifeProfile? {
        val raw = prefs.getString(KEY_OIL, null) ?: return null
        return runCatching { parseOilProfile(JSONObject(raw)) }
            .onFailure { Log.w(TAG, "Oil profile unreadable, ignoring", it) }
            .getOrNull()
    }

    override fun save(profile: OilLifeProfile) {
        prefs.edit().putString(KEY_OIL, oilProfileToJson(profile).toString()).apply()
    }
}

private fun oilProfileToJson(p: OilLifeProfile): JSONObject = JSONObject()
    .put("lastResetTimestamp", p.lastResetTimestamp)
    .put("lastResetOdometer", p.lastResetOdometer)
    .put("currentOdometer", p.currentOdometer)
    .put("oilLifePercent", p.oilLifePercent)
    .put("accumulatedRevolutions", p.accumulatedRevolutions)
    .put("coldStartsCount", p.coldStartsCount)
    .put("timeBelowOperatingTempSec", p.timeBelowOperatingTempSec)
    .put("shortTripsCount", p.shortTripsCount)
    .put("highThermalStressSec", p.highThermalStressSec)
    .put("estimatedMilesRemaining", p.estimatedMilesRemaining)
    .put("estimatedDaysRemaining", p.estimatedDaysRemaining)
    .put("oilConditionGrade", p.oilConditionGrade.label)
    .put(
        "degradationBreakdown",
        JSONObject()
            .put("revWearFactor", p.degradationBreakdown.revWearFactor)
            .put("coldStartPenalty", p.degradationBreakdown.coldStartPenalty)
            .put("shortTripPenalty", p.degradationBreakdown.shortTripPenalty)
            .put("thermalShearPenalty", p.degradationBreakdown.thermalShearPenalty),
    )

private fun parseOilProfile(json: JSONObject): OilLifeProfile {
    val breakdown = json.optJSONObject("degradationBreakdown") ?: JSONObject()
    return OilLifeProfile(
        lastResetTimestamp = json.optLong("lastResetTimestamp", 0L),
        lastResetOdometer = json.optDouble("lastResetOdometer", 0.0),
        currentOdometer = json.optDouble("currentOdometer", 0.0),
        oilLifePercent = json.optDouble("oilLifePercent", 100.0),
        accumulatedRevolutions = json.optDouble("accumulatedRevolutions", 0.0),
        coldStartsCount = json.optInt("coldStartsCount", 0),
        timeBelowOperatingTempSec = json.optDouble("timeBelowOperatingTempSec", 0.0),
        shortTripsCount = json.optInt("shortTripsCount", 0),
        highThermalStressSec = json.optDouble("highThermalStressSec", 0.0),
        estimatedMilesRemaining = json.optInt("estimatedMilesRemaining", 0),
        estimatedDaysRemaining = json.optInt("estimatedDaysRemaining", 0),
        oilConditionGrade = gradeFromLabel(json.optString("oilConditionGrade")),
        degradationBreakdown = DegradationBreakdown(
            revWearFactor = breakdown.optDouble("revWearFactor", 0.0),
            coldStartPenalty = breakdown.optDouble("coldStartPenalty", 0.0),
            shortTripPenalty = breakdown.optDouble("shortTripPenalty", 0.0),
            thermalShearPenalty = breakdown.optDouble("thermalShearPenalty", 0.0),
        ),
    )
}

private fun gradeFromLabel(label: String?): OilConditionGrade =
    OilConditionGrade.entries.firstOrNull { it.label == label } ?: OilConditionGrade.GOOD

// ── Fuel blend ───────────────────────────────────────────────────────────────────

fun loadFuelBlend(context: Context): FuelBlendId {
    val stored = telemetryPrefs(context).getString(KEY_FUEL_BLEND, null)
    return FuelBlendId.entries.firstOrNull { it.name == stored } ?: FuelBlendId.E10
}

fun saveFuelBlend(context: Context, id: FuelBlendId) {
    telemetryPrefs(context).edit().putString(KEY_FUEL_BLEND, id.name).apply()
}

// ── The one-time migration ───────────────────────────────────────────────────────

/**
 * Imports the records rescued out of the Capacitor app's WebView storage.
 *
 * Runs once, guarded by its own flag rather than by "is the record empty" - a record that is
 * legitimately empty because someone reset it must not be silently refilled with figures from
 * a year ago. Nothing is overwritten either: if a record already exists, the import is skipped
 * and says so.
 *
 * These values cannot be regenerated. They accumulated over real driving, they were extracted
 * from a Snappy-compressed leveldb table with `adb run-as`, and the JSON in res/raw is the
 * only copy the app has. See migration-backup/ in the repo for the raw evidence.
 */
fun importRescuedRecordsOnce(context: Context): String? {
    val prefs = telemetryPrefs(context)
    if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) return null

    return runCatching {
        val text = context.resources.openRawResource(R.raw.rescued_localstorage)
            .bufferedReader()
            .use { it.readText() }
        val root = JSONObject(text)

        val messages = mutableListOf<String>()

        root.optJSONObject("civic_2013_lifetime_stats_v2")?.let { lifetimeJson ->
            if (prefs.getString(KEY_LIFETIME, null) == null) {
                val stats = parseLifetime(lifetimeJson)
                PrefsLifetimeStore(context).save(stats)
                messages += "lifetime ${"%.1f".format(stats.lifetimeMpg)} mpg over " +
                    "${"%.1f".format(stats.totalMiles)} mi"
            }
        }

        root.optJSONObject("civic_2013_oil_profile_v1")?.let { oilJson ->
            if (prefs.getString(KEY_OIL, null) == null) {
                val profile = parseOilProfile(oilJson)
                PrefsOilProfileStore(context).save(profile)
                messages += "oil life ${profile.oilLifePercent}%"
            }
        }

        root.optString("civic_2013_fuel_blend_v1").takeIf { it.isNotBlank() }?.let { blend ->
            FuelBlendId.entries.firstOrNull { it.name == blend }?.let { saveFuelBlend(context, it) }
        }

        prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()

        if (messages.isEmpty()) null else "Imported " + messages.joinToString(", ")
    }.onFailure {
        // Deliberately not marking the migration done: a parse failure should be retried on
        // the next launch rather than silently consuming the only copy of the data.
        Log.e(TAG, "Could not import the rescued records", it)
    }.getOrNull()
}
