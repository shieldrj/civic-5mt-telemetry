package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Typing a receipt into a notification")
class ReceiptInputTest {

    @Test
    fun `Gallons alone`() {
        val e = assertNotNull(ReceiptInput.parse("11.01"))
        assertEquals(11.01, e.pumpGallons, 1e-9)
        assertNull(e.odometerMiles)
        assertTrue(e.filledToShutoff)
    }

    @Test
    fun `Gallons then odometer`() {
        val e = assertNotNull(ReceiptInput.parse("11.01 197214"))
        assertEquals(11.01, e.pumpGallons, 1e-9)
        assertEquals(197_214.0, e.odometerMiles)
    }

    @Test
    fun `Order does not matter because the sizes cannot be confused`() {
        val e = assertNotNull(ReceiptInput.parse("197214 11.01"))
        assertEquals(11.01, e.pumpGallons, 1e-9)
        assertEquals(197_214.0, e.odometerMiles)
    }

    @Test
    fun `A thousands separator in the odometer is understood`() {
        val e = assertNotNull(ReceiptInput.parse("197,214 11.01"))
        assertEquals(197_214.0, e.odometerMiles)
        assertEquals(11.01, e.pumpGallons, 1e-9)
    }

    @Test
    fun `Words around the numbers are ignored`() {
        val e = assertNotNull(ReceiptInput.parse("  11.01 gal, odo 197214 mi "))
        assertEquals(11.01, e.pumpGallons, 1e-9)
        assertEquals(197_214.0, e.odometerMiles)
    }

    @Test
    fun `Saying it was a partial fill is understood`() {
        assertFalse(assertNotNull(ReceiptInput.parse("6.4 partial")).filledToShutoff)
        assertFalse(assertNotNull(ReceiptInput.parse("top up 3.2")).filledToShutoff)
    }

    @Test
    fun `Anything ambiguous is refused rather than guessed`() {
        assertNull(ReceiptInput.parse(null))
        assertNull(ReceiptInput.parse(""))
        assertNull(ReceiptInput.parse("   "))
        assertNull(ReceiptInput.parse("full"))
        assertNull(ReceiptInput.parse("0"))
        // A decimal comma is not a thousands separator, and reading it as two numbers
        // would mean two volumes of fuel.
        assertNull(ReceiptInput.parse("11,01"))
        assertNull(ReceiptInput.parse("11 12"))
        assertNull(ReceiptInput.parse("1200 1300"))
        assertNull(ReceiptInput.parse("11.01 197214 5"))
        assertNull(ReceiptInput.parse("11.01 9999999"))
    }
}
