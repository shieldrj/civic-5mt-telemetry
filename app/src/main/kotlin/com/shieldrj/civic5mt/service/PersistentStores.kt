package com.shieldrj.civic5mt.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.shieldrj.civic5mt.R
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.ClutchConditionGrade
import com.shieldrj.civic5mt.core.ClutchProfile
import com.shieldrj.civic5mt.core.ClutchProfileStore
import com.shieldrj.civic5mt.core.ClutchSlipIncident
import com.shieldrj.civic5mt.core.ClutchWearBreakdown
import com.shieldrj.civic5mt.core.DegradationBreakdown
import com.shieldrj.civic5mt.core.FuelBlendId
import com.shieldrj.civic5mt.core.GasPrice
import com.shieldrj.civic5mt.core.GasPriceSnapshot
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LifetimeStore
import com.shieldrj.civic5mt.core.OilConditionGrade
import com.shieldrj.civic5mt.core.OilLifeProfile
import com.shieldrj.civic5mt.core.OilProfileStore
import com.shieldrj.civic5mt.core.TankState
import com.shieldrj.civic5mt.core.TankStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Where the records that have to survive a restart are kept.
 *
 * Deliberately not Room. These are single rows, read once at startup and written at most
 * every thirty seconds; a database for them would be ceremony around a key-value pair. Room
 * earns its place when the trip history lands - queryable drives and time-series logging are
 * what it is actually for.
 *
 * All are written as JSON rather than as individual preference keys, so a schema change is
 * one parse to reason about, and so the shape on disk matches the shape of the JSON rescued
 * out of the WebView. That symmetry is what makes the migration a straight read.
 */
private const val PREFS_NAME = "civic_telemetry"
private const val KEY_LIFETIME = "civic_2013_lifetime_stats_v2"
private const val KEY_OIL = "civic_2013_oil_profile_v1"
// v2: v1 records were accumulated against a 42 MJ lifetime budget and seeded from a
// fabricated 114,250-mile history, so their energy totals and odometer mean nothing
// under the corrected model. Better to start counting honestly than to reinterpret them.
private const val KEY_CLUTCH = "civic_2013_clutch_profile_v2"
private const val KEY_FUEL_BLEND = "civic_2013_fuel_blend_v1"
private const val KEY_MIGRATION_DONE = "rescued_localstorage_imported_v1"
private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
private const val KEY_OVERLAY_X = "overlay_x"
private const val KEY_OVERLAY_Y = "overlay_y"
private const val KEY_HUD_THEME = "hud_theme"
private const val KEY_LAST_ADAPTER = "last_adapter_address"
private const val KEY_TANK = "civic_2013_tank_v1"
private const val KEY_WIDGET_SNAPSHOT = "widget_snapshot_v1"
private const val KEY_AUTO_CONNECT = "auto_connect"
private const val KEY_BACKUP_TREE_URI = "backup_tree_uri"
private const val KEY_BACKUP_DOC_URI = "backup_doc_uri"
private const val KEY_LAST_BACKUP_AT = "last_backup_at"
private const val KEY_GAS_PRICES = "costco_gas_prices_v1"

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
        prefs.edit().putString(KEY_LIFETIME, lifetimeToJson(stats).toString()).apply()
    }
}

/**
 * Named, and beside its parser, for the same reason the oil and tank encoders are: the pair
 * has to agree about every key, and a round-trip test can only hold them to that if it can
 * reach both. This one used to be written inline in [PrefsLifetimeStore.save], which put the
 * only encoder of the one irreplaceable record where no test could see it.
 */
internal fun lifetimeToJson(stats: LifetimeStats): JSONObject = JSONObject()
    .put("totalMiles", stats.totalMiles)
    .put("totalFuelGallons", stats.totalFuelGallons)
    // Stored for compatibility with the WebView shape. It is recomputed on load - miles and
    // gallons are the measurements, the ratio is a view of them.
    .put("lifetimeMpg", stats.lifetimeMpg)
    .put("firstTrackedTimestamp", stats.firstTrackedTimestamp)

internal fun parseLifetime(json: JSONObject): LifetimeStats = LifetimeStats(
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

internal fun oilProfileToJson(p: OilLifeProfile): JSONObject = JSONObject()
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
    .put("estimatedDaysRemaining", p.estimatedDaysRemaining ?: JSONObject.NULL)
    .put("oilConditionGrade", p.oilConditionGrade.label)
    .put(
        "degradationBreakdown",
        JSONObject()
            .put("revWearFactor", p.degradationBreakdown.revWearFactor)
            .put("coldStartPenalty", p.degradationBreakdown.coldStartPenalty)
            .put("shortTripPenalty", p.degradationBreakdown.shortTripPenalty)
            .put("thermalShearPenalty", p.degradationBreakdown.thermalShearPenalty),
    )

internal fun parseOilProfile(json: JSONObject): OilLifeProfile {
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
        estimatedDaysRemaining =
            if (json.isNull("estimatedDaysRemaining")) null
            else json.optInt("estimatedDaysRemaining").takeIf { it > 0 },
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

// ── Clutch profile ───────────────────────────────────────────────────────────────

class PrefsClutchProfileStore(context: Context) : ClutchProfileStore {
    private val prefs = telemetryPrefs(context)

    override fun load(): ClutchProfile? {
        val raw = prefs.getString(KEY_CLUTCH, null) ?: return null
        return runCatching { parseClutchProfile(JSONObject(raw)) }
            .onFailure { Log.w(TAG, "Clutch profile unreadable, ignoring", it) }
            .getOrNull()
    }

    override fun save(profile: ClutchProfile) {
        prefs.edit().putString(KEY_CLUTCH, clutchProfileToJson(profile).toString()).apply()
    }
}

internal fun clutchProfileToJson(p: ClutchProfile): JSONObject {
    val incidentsArray = JSONArray()
    p.recentIncidents.forEach { incident ->
        incidentsArray.put(
            JSONObject()
                .put("timestamp", incident.timestamp)
                .put("gear", incident.gear)
                .put("peakSlipRpm", incident.peakSlipRpm)
                .put("peakTorqueNm", incident.peakTorqueNm)
                .put("speedKmh", incident.speedKmh)
                .put("durationSec", incident.durationSec)
        )
    }

    return JSONObject()
        .put("lastResetTimestamp", p.lastResetTimestamp)
        .put("lastResetOdometer", p.lastResetOdometer)
        .put("currentOdometer", p.currentOdometer)
        .put("clutchHealthPercent", p.clutchHealthPercent)
        .put("accumulatedFrictionEnergyJoules", p.accumulatedFrictionEnergyJoules)
        .put("totalEngagementsCount", p.totalEngagementsCount)
        .put("abnormalSlipCount", p.abnormalSlipCount)
        .put("maxObservedTempC", p.maxObservedTempC)
        .put("estimatedTorqueCapacityNm", p.estimatedTorqueCapacityNm)
        .put("observedCapacityFloorNm", p.observedCapacityFloorNm)
        .put("baselineKnown", p.baselineKnown)
        .put("ratioCalibration", p.ratioCalibration)
        .put("estimatedMilesRemaining", p.estimatedMilesRemaining ?: JSONObject.NULL)
        .put("estimatedDaysRemaining", p.estimatedDaysRemaining ?: JSONObject.NULL)
        .put("estimatedShiftsRemaining", p.estimatedShiftsRemaining)
        .put("conditionGrade", p.conditionGrade.label)
        .put(
            "degradationBreakdown",
            JSONObject()
                .put("shiftWearPercent", p.degradationBreakdown.shiftWearPercent)
                .put("launchWearPercent", p.degradationBreakdown.launchWearPercent)
                .put("slipWearPercent", p.degradationBreakdown.slipWearPercent)
                .put("thermalGlazePenaltyPercent", p.degradationBreakdown.thermalGlazePenaltyPercent),
        )
        .put("recentIncidents", incidentsArray)
}

internal fun parseClutchProfile(json: JSONObject): ClutchProfile {
    val breakdown = json.optJSONObject("degradationBreakdown") ?: JSONObject()
    val incidentsArray = json.optJSONArray("recentIncidents") ?: JSONArray()
    val incidents = mutableListOf<ClutchSlipIncident>()

    for (i in 0 until incidentsArray.length()) {
        val obj = incidentsArray.optJSONObject(i) ?: continue
        incidents.add(
            ClutchSlipIncident(
                timestamp = obj.optLong("timestamp", 0L),
                gear = obj.optInt("gear", 1),
                peakSlipRpm = obj.optDouble("peakSlipRpm", 0.0),
                peakTorqueNm = obj.optDouble("peakTorqueNm", 0.0),
                speedKmh = obj.optDouble("speedKmh", 0.0),
                durationSec = obj.optDouble("durationSec", 0.0),
            )
        )
    }

    return ClutchProfile(
        lastResetTimestamp = json.optLong("lastResetTimestamp", 0L),
        lastResetOdometer = json.optDouble("lastResetOdometer", 0.0),
        currentOdometer = json.optDouble("currentOdometer", 0.0),
        clutchHealthPercent = json.optDouble("clutchHealthPercent", 100.0),
        accumulatedFrictionEnergyJoules = json.optDouble("accumulatedFrictionEnergyJoules", 0.0),
        totalEngagementsCount = json.optInt("totalEngagementsCount", 0),
        abnormalSlipCount = json.optInt("abnormalSlipCount", 0),
        maxObservedTempC = json.optDouble("maxObservedTempC", 25.0),
        estimatedTorqueCapacityNm = json.optDouble(
            "estimatedTorqueCapacityNm",
            CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM,
        ),
        observedCapacityFloorNm = json.optDouble(
            "observedCapacityFloorNm",
            CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM,
        ),
        // Absent on any record this app wrote before the disc was tracked from new, which
        // is the honest default: it did not watch the clutch from new.
        baselineKnown = json.optBoolean("baselineKnown", false),
        ratioCalibration = json.optDouble("ratioCalibration", 1.0)
            .coerceIn(CivicSpecs.CLUTCH_CALIBRATION_MIN, CivicSpecs.CLUTCH_CALIBRATION_MAX),
        // Both projections are recomputed on load anyway; null is what "not yet known"
        // looks like, and it beats restoring a guess of 120,000 miles.
        estimatedMilesRemaining =
            if (json.isNull("estimatedMilesRemaining")) null
            else json.optInt("estimatedMilesRemaining").takeIf { it > 0 },
        estimatedDaysRemaining =
            if (json.isNull("estimatedDaysRemaining")) null
            else json.optInt("estimatedDaysRemaining").takeIf { it > 0 },
        estimatedShiftsRemaining = json.optInt("estimatedShiftsRemaining", 0),
        conditionGrade = clutchGradeFromLabel(json.optString("conditionGrade")),
        degradationBreakdown = ClutchWearBreakdown(
            shiftWearPercent = breakdown.optDouble("shiftWearPercent", 0.0),
            launchWearPercent = breakdown.optDouble("launchWearPercent", 0.0),
            slipWearPercent = breakdown.optDouble("slipWearPercent", 0.0),
            thermalGlazePenaltyPercent = breakdown.optDouble("thermalGlazePenaltyPercent", 0.0),
        ),
        recentIncidents = incidents,
    )
}

private fun clutchGradeFromLabel(label: String?): ClutchConditionGrade =
    ClutchConditionGrade.entries.firstOrNull { it.label == label } ?: ClutchConditionGrade.GOOD

// ── Fuel blend ───────────────────────────────────────────────────────────────────

fun loadFuelBlend(context: Context): FuelBlendId {
    val stored = telemetryPrefs(context).getString(KEY_FUEL_BLEND, null)
    return FuelBlendId.entries.firstOrNull { it.name == stored } ?: FuelBlendId.E10
}

fun saveFuelBlend(context: Context, id: FuelBlendId) {
    telemetryPrefs(context).edit().putString(KEY_FUEL_BLEND, id.name).apply()
}

// ── The current tank ─────────────────────────────────────────────────────────────

/**
 * Where the tank in the car is kept between runs.
 *
 * It has to survive the app being closed, which is most of the point: a tank lasts a fortnight
 * and the app is not open for all of it. The gallons-per-percent figure matters even more -
 * it is measured once per tank, so losing it means going back to the nominal number and
 * measuring again from scratch.
 */
class PrefsTankStore(context: Context) : TankStore {
    private val prefs = telemetryPrefs(context)

    override fun load(): TankState? {
        val raw = prefs.getString(KEY_TANK, null) ?: return null
        return runCatching { parseTank(JSONObject(raw)) }
            .onFailure { Log.w(TAG, "Tank record unreadable, ignoring", it) }
            .getOrNull()
    }

    override fun save(state: TankState) {
        prefs.edit().putString(KEY_TANK, tankToJson(state).toString()).apply()
    }
}

internal fun tankToJson(state: TankState): JSONObject = JSONObject()
    .put("fillTimestamp", state.fillTimestamp)
    .put("levelPercentAtFill", state.levelPercentAtFill)
    .put("milesSinceFill", state.milesSinceFill)
    .put("gallonsUsedSinceFill", state.gallonsUsedSinceFill)
    .put("gallonsPerPercent", state.gallonsPerPercent)
    .put("calibrated", state.calibrated)
    .put("smoothedLevelPercent", state.smoothedLevelPercent)
    .put("lowestLevelPercent", state.lowestLevelPercent)
    .put("fullMarkPercent", state.fullMarkPercent)

internal fun parseTank(j: JSONObject): TankState = TankState(
    fillTimestamp = j.optLong("fillTimestamp", 0L),
    levelPercentAtFill = j.optDouble("levelPercentAtFill", 0.0),
    milesSinceFill = j.optDouble("milesSinceFill", 0.0),
    gallonsUsedSinceFill = j.optDouble("gallonsUsedSinceFill", 0.0),
    gallonsPerPercent = j.optDouble("gallonsPerPercent", 0.132),
    calibrated = j.optBoolean("calibrated", false),
    smoothedLevelPercent = j.optDouble("smoothedLevelPercent", 0.0),
    lowestLevelPercent = j.optDouble("lowestLevelPercent", 100.0),
    // Zero on a record written before the full mark existed, which reads as "not seen yet"
    // and is exactly right: the percentage falls back to the sender's own scale until the
    // next fill teaches it where full is.
    fullMarkPercent = j.optDouble("fullMarkPercent", 0.0),
)

// ── The adapter last used ────────────────────────────────────────────────────────

/**
 * Which paired device turned out to be the one in the car.
 *
 * A phone is bonded to headphones, a watch, a speaker and a car stereo, and exactly one of
 * them answers an ELM327 handshake. Remembering the one that did turns connecting from
 * reading a list into pressing the thing at the top - and it is what the reconnect logic
 * chases after a link drops.
 *
 * The address only, never a name: names come from the Bluetooth stack at read time and
 * change when a device is renamed, while the address is what actually opens a socket.
 */
fun loadLastAdapter(context: Context): String? =
    telemetryPrefs(context).getString(KEY_LAST_ADAPTER, null)

fun saveLastAdapter(context: Context, address: String) {
    telemetryPrefs(context).edit().putString(KEY_LAST_ADAPTER, address).apply()
}

// ── Overlay preference ───────────────────────────────────────────────────────────

fun loadOverlayEnabled(context: Context): Boolean =
    telemetryPrefs(context).getBoolean(KEY_OVERLAY_ENABLED, false)

fun saveOverlayEnabled(context: Context, enabled: Boolean) {
    telemetryPrefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
}

/**
 * Where the driver last dragged the heads-up display.
 *
 * The right spot depends on the phone mount and on which corner of the map matters, and
 * whoever is driving has already decided it - so the decision is kept rather than made again
 * on every show().
 */
fun loadOverlayPosition(context: Context): Pair<Int, Int>? {
    val prefs = telemetryPrefs(context)
    val x = prefs.getInt(KEY_OVERLAY_X, Int.MIN_VALUE)
    val y = prefs.getInt(KEY_OVERLAY_Y, Int.MIN_VALUE)
    return if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) null else x to y
}

fun saveOverlayPosition(context: Context, x: Int, y: Int) {
    telemetryPrefs(context).edit()
        .putInt(KEY_OVERLAY_X, x)
        .putInt(KEY_OVERLAY_Y, y)
        .apply()
}

// ── The HUD's look ───────────────────────────────────────────────────────────────

/** Google Maps themes itself independently of the phone, so which card matches is a choice. */
fun loadHudTheme(context: Context): HudTheme =
    HudTheme.entries.firstOrNull {
        it.name == telemetryPrefs(context).getString(KEY_HUD_THEME, null)
    } ?: HudTheme.SYSTEM

fun saveHudTheme(context: Context, theme: HudTheme) {
    telemetryPrefs(context).edit().putString(KEY_HUD_THEME, theme.name).apply()
}

// ── The home-screen widget's last known figures ──────────────────────────────────

/**
 * What the widget shows when the service is not running to push fresh numbers.
 *
 * The widget's own process is this process, but a widget sits on the launcher long after the
 * car is off, so its provider reads these instead of [com.shieldrj.civic5mt.service.TelemetryState]
 * - which only holds values while a drive is live. Written at most every thirty seconds from
 * the tick loop; a figure that is half a minute old is fine for a glance through a kitchen
 * window.
 */
/**
 * The two figures on the widget, and whether the second one is a reading or a ceiling.
 *
 * [rangeIsCeiling] rides along because it changes what [rangeMiles] means. Under the sender's
 * zero the miles stop counting down - see TankState.belowSenderZero - and a widget glanced at
 * through a kitchen window is the last place to lose that.
 */
data class WidgetSnapshot(
    val tankMpg: Double? = null,
    val rangeMiles: Int? = null,
    val rangeIsCeiling: Boolean = false,
)

fun saveWidgetSnapshot(context: Context, snapshot: WidgetSnapshot) {
    val json = JSONObject()
        .put("tankMpg", snapshot.tankMpg ?: JSONObject.NULL)
        .put("rangeMiles", snapshot.rangeMiles ?: JSONObject.NULL)
        .put("rangeIsCeiling", snapshot.rangeIsCeiling)
    telemetryPrefs(context).edit().putString(KEY_WIDGET_SNAPSHOT, json.toString()).apply()
}

fun loadWidgetSnapshot(context: Context): WidgetSnapshot {
    val raw = telemetryPrefs(context).getString(KEY_WIDGET_SNAPSHOT, null)
        ?: return WidgetSnapshot()
    return runCatching {
        val j = JSONObject(raw)
        WidgetSnapshot(
            tankMpg = if (j.isNull("tankMpg")) null else j.optDouble("tankMpg"),
            rangeMiles = if (j.isNull("rangeMiles")) null else j.optInt("rangeMiles"),
            // Absent in snapshots written before this existed, and false is the right reading
            // of those: they were saved by a build that only ever showed plain figures.
            rangeIsCeiling = j.optBoolean("rangeIsCeiling", false),
        )
    }.onFailure { Log.w(TAG, "Widget snapshot unreadable, ignoring", it) }
        .getOrDefault(WidgetSnapshot())
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

// ── Auto-connect ─────────────────────────────────────────────────────────────────

/**
 * Whether the app should start logging on its own when the adapter appears.
 *
 * The OBDLink powers up with the ignition, and the phone bonds to it the way it bonds to a
 * car stereo - so the moment the car turns on is a moment the phone can hear. Acting on that
 * is what makes cold start "just work": no app opening, no button, no remembering.
 */
fun loadAutoConnect(context: Context): Boolean =
    telemetryPrefs(context).getBoolean(KEY_AUTO_CONNECT, true)

fun saveAutoConnect(context: Context, enabled: Boolean) {
    telemetryPrefs(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
}

// ── Backup location ──────────────────────────────────────────────────────────────

/** The SAF folder the driver chose once, persisted so backups need no interaction. */
fun loadBackupTreeUri(context: Context): String? =
    telemetryPrefs(context).getString(KEY_BACKUP_TREE_URI, null)

fun loadBackupDocUri(context: Context): String? =
    telemetryPrefs(context).getString(KEY_BACKUP_DOC_URI, null)

fun saveBackupUris(context: Context, treeUri: String, docUri: String) {
    telemetryPrefs(context).edit()
        .putString(KEY_BACKUP_TREE_URI, treeUri)
        .putString(KEY_BACKUP_DOC_URI, docUri)
        .apply()
}

fun markBackedUp(context: Context, at: Long) {
    telemetryPrefs(context).edit().putLong(KEY_LAST_BACKUP_AT, at).apply()
}

fun loadLastBackupAt(context: Context): Long =
    telemetryPrefs(context).getLong(KEY_LAST_BACKUP_AT, 0L)

// ── The last Costco prices seen ───────────────────────────────────────────────

/**
 * Yesterday's pump prices, so the Fuel tab opens with figures rather than with dashes.
 *
 * Deliberately left out of the backup document: this is the one record here that regenerates
 * itself from the internet in a second, and a restored price from a fortnight ago would be
 * worse than no price at all. The fetch timestamp is stored beside it because a price with no
 * age on it cannot be judged.
 */
fun saveGasPrices(context: Context, snapshot: GasPriceSnapshot) {
    val prices = JSONObject()
    snapshot.prices.forEach { (warehouseId, price) ->
        prices.put(
            warehouseId,
            JSONObject()
                .put("regular", price.regular ?: JSONObject.NULL)
                .put("premium", price.premium ?: JSONObject.NULL),
        )
    }
    val json = JSONObject()
        .put("fetchedAt", snapshot.fetchedAt)
        .put("prices", prices)
    telemetryPrefs(context).edit().putString(KEY_GAS_PRICES, json.toString()).apply()
}

fun loadGasPrices(context: Context): GasPriceSnapshot {
    val raw = telemetryPrefs(context).getString(KEY_GAS_PRICES, null) ?: return GasPriceSnapshot()
    return runCatching {
        val json = JSONObject(raw)
        val prices = json.optJSONObject("prices") ?: JSONObject()
        val parsed = prices.keys().asSequence().associateWith { warehouseId ->
            val entry = prices.getJSONObject(warehouseId)
            GasPrice(
                regular = if (entry.isNull("regular")) null else entry.optDouble("regular"),
                premium = if (entry.isNull("premium")) null else entry.optDouble("premium"),
            )
        }
        GasPriceSnapshot(parsed, json.optLong("fetchedAt", 0L))
    }.onFailure { Log.w(TAG, "Stored gas prices unreadable, ignoring", it) }
        .getOrDefault(GasPriceSnapshot())
}

// ── The backup document itself ───────────────────────────────────────────────────

/**
 * Everything irreplaceable, as one JSON document.
 *
 * The raw preference strings are embedded verbatim rather than re-encoded field by field:
 * the shapes on disk are already the shapes this app parses, and a backup that is a copy
 * cannot drift from what a restore expects to read.
 */
fun snapshotRecords(context: Context): JSONObject {
    val prefs = telemetryPrefs(context)
    return JSONObject()
        .put("exportedAt", System.currentTimeMillis())
        .put(KEY_LIFETIME, prefs.getString(KEY_LIFETIME, null) ?: JSONObject.NULL)
        .put(KEY_OIL, prefs.getString(KEY_OIL, null) ?: JSONObject.NULL)
        .put(KEY_CLUTCH, prefs.getString(KEY_CLUTCH, null) ?: JSONObject.NULL)
        .put(KEY_TANK, prefs.getString(KEY_TANK, null) ?: JSONObject.NULL)
        .put(KEY_FUEL_BLEND, prefs.getString(KEY_FUEL_BLEND, null) ?: JSONObject.NULL)
}

/**
 * Fills in whatever the backup has that this phone does not.
 *
 * Deliberately conservative: a record that exists locally is never overwritten by a restore,
 * because the local copy is at least as new as any backup of it. That makes restore safe to
 * offer without a confirmation dialog - on a fresh install (the case that matters: a lost or
 * replaced phone) every record is missing and everything comes back; on a live phone nothing
 * is missing and nothing changes.
 */
fun restoreRecords(context: Context, backup: JSONObject): List<String> {
    val prefs = telemetryPrefs(context)
    val messages = mutableListOf<String>()

    if (prefs.getString(KEY_LIFETIME, null) == null && !backup.isNull(KEY_LIFETIME)) {
        runCatching { parseLifetime(JSONObject(backup.getString(KEY_LIFETIME))) }.getOrNull()?.let {
            PrefsLifetimeStore(context).save(it)
            messages += "lifetime ${"%.1f".format(it.lifetimeMpg)} mpg over ${"%.1f".format(it.totalMiles)} mi"
        }
    }

    if (prefs.getString(KEY_OIL, null) == null && !backup.isNull(KEY_OIL)) {
        runCatching { parseOilProfile(JSONObject(backup.getString(KEY_OIL))) }.getOrNull()?.let {
            PrefsOilProfileStore(context).save(it)
            messages += "oil life ${it.oilLifePercent.roundToInt()}%"
        }
    }

    if (prefs.getString(KEY_CLUTCH, null) == null && !backup.isNull(KEY_CLUTCH)) {
        runCatching { parseClutchProfile(JSONObject(backup.getString(KEY_CLUTCH))) }.getOrNull()?.let {
            PrefsClutchProfileStore(context).save(it)
            messages += "clutch health ${it.clutchHealthPercent.roundToInt()}%"
        }
    }

    if (prefs.getString(KEY_TANK, null) == null && !backup.isNull(KEY_TANK)) {
        runCatching { parseTank(JSONObject(backup.getString(KEY_TANK))) }.getOrNull()?.let {
            PrefsTankStore(context).save(it)
            messages += "tank state"
        }
    }

    backup.optString(KEY_FUEL_BLEND).takeIf { it.isNotBlank() }?.let { blend ->
        FuelBlendId.entries.firstOrNull { it.name == blend }?.let { saveFuelBlend(context, it) }
    }

    return messages
}
