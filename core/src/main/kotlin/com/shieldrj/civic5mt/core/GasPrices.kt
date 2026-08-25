package com.shieldrj.civic5mt.core

/**
 * What the three Costco pumps are charging today.
 *
 * Costco does not publish a price API, and its website is behind bot protection that refuses
 * a plain request for either the warehouse page or the old warehouse-lookup endpoint. One
 * thing does answer: the small service the locator page calls to fill in the price under each
 * result. It takes warehouse numbers joined by underscores and answers with nothing but
 * prices - no session, no key, no member number:
 *
 *     GET /AjaxGetGasPricesService?warehouseid=1015_473_677
 *     {"473":{"premium":"5.599","regular":"5.259"},"1015":{...},"677":{...}}
 *
 * It does insist on looking like a browser - a request with curl's own User-Agent has the
 * connection reset - so [COSTCO_BROWSER_USER_AGENT] goes on every call.
 *
 * The warehouse numbers were read off Costco's own locator, not guessed: a warehouse number
 * is not a postcode and the numbers near each other are in different towns.
 */
data class CostcoStation(
    val warehouseId: String,
    val name: String,
)

/**
 * The three warehouses on the list, in the order they are shown.
 *
 * Adding a fourth is one line here and nothing else - the URL, the parse and the screen all
 * read this list rather than knowing how long it is.
 */
val COSTCO_STATIONS: List<CostcoStation> = listOf(
    CostcoStation("1015", "San Dimas"),
    CostcoStation("473", "Chino Hills"),
    CostcoStation("677", "Burbank"),
)

/**
 * A posted price, in dollars per gallon.
 *
 * Both grades are nullable because the service leaves out what it does not have: a warehouse
 * whose pumps are down answers with an empty object, and a business centre with no petrol at
 * all answers with premium only. Missing is not zero, and a station with no price has to be
 * drawn differently from one selling fuel for nothing.
 */
data class GasPrice(
    val regular: Double? = null,
    val premium: Double? = null,
)

/** What the Fuel tab draws: prices by warehouse number, and when they were fetched. */
data class GasPriceSnapshot(
    val prices: Map<String, GasPrice> = emptyMap(),
    val fetchedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = prices.isEmpty()

    fun ageMillis(now: Long): Long = if (fetchedAt <= 0L) Long.MAX_VALUE else now - fetchedAt

    /**
     * Whether it is worth asking again. Costco moves a price once a day at most, so a figure
     * from half an hour ago is the same figure - and the Fuel tab is opened at every stop.
     */
    fun isStale(now: Long, maxAgeMillis: Long = GAS_PRICE_MAX_AGE_MS): Boolean =
        ageMillis(now) > maxAgeMillis
}

const val GAS_PRICE_MAX_AGE_MS: Long = 30 * 60 * 1000L

const val COSTCO_BROWSER_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

fun costcoGasPriceUrl(stations: List<CostcoStation> = COSTCO_STATIONS): String =
    "https://www.costco.com/AjaxGetGasPricesService?warehouseid=" +
        stations.joinToString("_") { it.warehouseId }

// The response is two levels deep and holds only strings, so it is read with a pair of
// patterns rather than a JSON library. That keeps this module free of a parser dependency
// and, more to the point, keeps the parse in the module the tests actually run in - the
// shapes below are the ones that came back from the real service, including the empty and
// premium-only ones.
private val WAREHOUSE_BLOCK = Regex("""["]([0-9]+)["]\s*:\s*\{([^}]*)\}""")
private val GRADE_ENTRY = Regex("""["]([a-zA-Z]+)["]\s*:\s*["]([0-9.]+)["]""")

/**
 * Reads the service's answer.
 *
 * Anything unreadable comes back as no entry rather than as an exception: a price that cannot
 * be parsed and a price that was never fetched are the same thing on screen, and neither is
 * worth interrupting a drive for. A price outside [0.50, 20.00] a gallon is dropped too -
 * that is not a cheap tank, it is a changed response shape.
 */
fun parseCostcoGasPrices(json: String): Map<String, GasPrice> =
    WAREHOUSE_BLOCK.findAll(json).mapNotNull { block ->
        val warehouseId = block.groupValues[1]
        val grades = GRADE_ENTRY.findAll(block.groupValues[2]).associate { entry ->
            entry.groupValues[1].lowercase() to entry.groupValues[2].toDoubleOrNull()
        }
        val price = GasPrice(
            regular = grades["regular"]?.takeIf { it.isPlausiblePumpPrice() },
            premium = grades["premium"]?.takeIf { it.isPlausiblePumpPrice() },
        )
        if (price.regular == null && price.premium == null) null else warehouseId to price
    }.toMap()

private fun Double.isPlausiblePumpPrice(): Boolean = this in 0.50..20.00
