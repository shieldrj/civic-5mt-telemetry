package com.shieldrj.civic5mt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bodies below are real answers from Costco's price service, captured while the three
 * warehouses were being identified. The empty and premium-only cases are not invented: a
 * business centre answered with premium alone and a third warehouse answered with nothing.
 */
class GasPricesTest {

    private val realResponse =
        """{"473":{"premium":"5.599","regular":"5.259"},"1015":{"premium":"5.659","regular":"5.349"},"677":{"premium":"5.799","regular":"5.399"}}"""

    @Test
    fun `reads all three warehouses`() {
        val prices = parseCostcoGasPrices(realResponse)
        assertEquals(3, prices.size)
        assertEquals(5.259, prices.getValue("473").regular)
        assertEquals(5.349, prices.getValue("1015").regular)
        assertEquals(5.399, prices.getValue("677").regular)
        assertEquals(5.799, prices.getValue("677").premium)
    }

    @Test
    fun `a warehouse with no prices is absent rather than zero`() {
        val prices = parseCostcoGasPrices("""{"653":{"premium":"4.279"},"677":{"regular":"5.399"},"130":{}}""")
        assertEquals(setOf("653", "677"), prices.keys)
        assertEquals(4.279, prices.getValue("653").premium)
        assertNull(prices.getValue("653").regular)
        assertNull(prices.getValue("677").premium)
    }

    @Test
    fun `nonsense parses to nothing rather than throwing`() {
        assertTrue(parseCostcoGasPrices("").isEmpty())
        assertTrue(parseCostcoGasPrices("<HTML><HEAD><TITLE>Access Denied</TITLE>").isEmpty())
        assertTrue(parseCostcoGasPrices("{}").isEmpty())
    }

    @Test
    fun `a price outside pump range is dropped`() {
        // A shape change - cents rather than dollars, or a sentinel - must not be drawn as a
        // bargain. 0.0 is the one that would otherwise read as free fuel.
        val prices = parseCostcoGasPrices("""{"1015":{"regular":"0.000","premium":"539.9"},"473":{"regular":"5.259"}}""")
        assertEquals(setOf("473"), prices.keys)
    }

    @Test
    fun `the url asks for exactly the stations on the list`() {
        assertEquals(
            "https://www.costco.com/AjaxGetGasPricesService?warehouseid=1015_473_677",
            costcoGasPriceUrl(),
        )
        assertEquals(3, COSTCO_STATIONS.size)
        assertEquals(listOf("San Dimas", "Chino Hills", "Burbank"), COSTCO_STATIONS.map { it.name })
    }

    @Test
    fun `a snapshot that was never fetched is always stale`() {
        val never = GasPriceSnapshot()
        assertTrue(never.isEmpty)
        assertTrue(never.isStale(now = 1_000_000L))

        val fresh = GasPriceSnapshot(mapOf("473" to GasPrice(regular = 5.259)), fetchedAt = 1_000_000L)
        assertFalse(fresh.isStale(now = 1_000_000L + 60_000L))
        assertTrue(fresh.isStale(now = 1_000_000L + GAS_PRICE_MAX_AGE_MS + 1L))
    }
}
