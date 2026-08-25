package com.shieldrj.civic5mt.data

import android.content.Context
import android.util.Log
import com.shieldrj.civic5mt.core.COSTCO_BROWSER_USER_AGENT
import com.shieldrj.civic5mt.core.GasPrice
import com.shieldrj.civic5mt.core.GasPriceSnapshot
import com.shieldrj.civic5mt.core.costcoGasPriceUrl
import com.shieldrj.civic5mt.core.parseCostcoGasPrices
import com.shieldrj.civic5mt.service.loadGasPrices
import com.shieldrj.civic5mt.service.saveGasPrices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The pump prices, fetched and remembered.
 *
 * A singleton for the same reason [com.shieldrj.civic5mt.service.TelemetryState] is one: the
 * answer is about the world rather than about a screen, and it should not be re-fetched
 * because a tab was closed and opened. Unlike that object this one is not written by the
 * service - nothing about the car produces it, and it must keep working with no adapter
 * anywhere near the phone.
 *
 * The last answer is written to preferences, so the Fuel tab has figures to draw at the
 * moment it opens: the fetch takes a second and a blank row that fills itself in is worse to
 * read at a pump than yesterday's price labelled as yesterday's.
 */
object GasPriceRepository {

    private const val TAG = "GasPrices"
    private const val TIMEOUT_MS = 8_000

    private val _snapshot = MutableStateFlow(GasPriceSnapshot())
    val snapshot: StateFlow<GasPriceSnapshot> = _snapshot.asStateFlow()

    /** True only while a request is in flight, so the screen can say so rather than freeze. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Whether the last attempt failed. Kept apart from the snapshot on purpose: a failed
     * refresh must not throw away prices that are merely old, because old prices are what
     * this screen is for when there is no signal at the pump.
     */
    private val _lastAttemptFailed = MutableStateFlow(false)
    val lastAttemptFailed: StateFlow<Boolean> = _lastAttemptFailed.asStateFlow()

    // One request at a time. Two tabs opening at once would otherwise both see a stale
    // snapshot and both go and ask.
    private val gate = Mutex()
    private var loadedFromDisk = false

    /**
     * Brings back the last answer this phone got, once per process.
     *
     * Only ever fills a gap. A fetch that landed while this was reading the file is newer
     * than the file by definition, and priming must not stand on top of it.
     */
    private suspend fun primeFromDisk(context: Context) {
        if (loadedFromDisk) return
        loadedFromDisk = true
        val stored = withContext(Dispatchers.IO) { loadGasPrices(context) }
        if (!stored.isEmpty && _snapshot.value.isEmpty) _snapshot.value = stored
    }

    /**
     * Fetches only if what is held is old enough to be a different price.
     *
     * Called every time the Fuel tab opens, which is often; the staleness check is what keeps
     * that from being a request every time.
     */
    suspend fun refreshIfStale(context: Context, now: Long = System.currentTimeMillis()) {
        primeFromDisk(context)
        if (_snapshot.value.isStale(now)) refresh(context)
    }

    /** Asks Costco now, whatever the age of what is held. This is what the tap does. */
    suspend fun refresh(context: Context) {
        primeFromDisk(context)
        if (!gate.tryLock()) return
        try {
            _refreshing.value = true
            val fetched = withContext(Dispatchers.IO) { fetch() }
            if (fetched == null || fetched.isEmpty()) {
                // Kept, not cleared. See [lastAttemptFailed].
                _lastAttemptFailed.value = true
            } else {
                val snapshot = GasPriceSnapshot(fetched, System.currentTimeMillis())
                _snapshot.value = snapshot
                _lastAttemptFailed.value = false
                withContext(Dispatchers.IO) { saveGasPrices(context, snapshot) }
            }
        } finally {
            _refreshing.value = false
            gate.unlock()
        }
    }

    /**
     * The request itself.
     *
     * [HttpURLConnection] rather than a HTTP library, because the whole exchange is one GET
     * of a couple of hundred bytes and the app has no other network call to justify a
     * dependency. The User-Agent is not decoration: without a browser-like one the connection
     * is reset before any response arrives.
     */
    private fun fetch(): Map<String, GasPrice>? = runCatching {
        val connection = (URL(costcoGasPriceUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", COSTCO_BROWSER_USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Costco answered ${connection.responseCode}")
                return@runCatching null
            }
            parseCostcoGasPrices(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.onFailure {
        // No signal in a car park is the ordinary case, not an error worth surfacing beyond
        // the "couldn't reach Costco" line on the tab.
        Log.w(TAG, "Could not fetch gas prices", it)
    }.getOrNull()
}
