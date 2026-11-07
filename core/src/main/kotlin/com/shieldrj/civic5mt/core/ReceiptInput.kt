package com.shieldrj.civic5mt.core

/** What a driver typed for a fill-up, once it has been understood. */
data class ReceiptEntry(
    val pumpGallons: Double,
    /** The odometer as the driver read it, or null when none was given. */
    val odometerMiles: Double?,
    val filledToShutoff: Boolean,
)

/**
 * Reads a receipt typed as one line of free text - which is all a notification's reply box
 * can take.
 *
 * "11.01", "11.01 197214", "197,214 11.01" and "11.01 gal partial" all mean what they look
 * like. The two numbers are told apart by size rather than by order, because someone typing
 * with a thumb in a notification shade will not reliably put them the same way round: no
 * tank holds a thousand gallons and no Civic has under a thousand miles on the clock.
 *
 * Anything ambiguous is refused rather than guessed. A receipt is the one measurement in
 * this app that is not an inference, and a misread one would calibrate the gauge wrongly.
 */
object ReceiptInput {

    /** At or above this a number is an odometer, below it a volume of fuel. */
    const val MIN_ODOMETER = 1000.0
    const val MAX_ODOMETER = 2_000_000.0

    private val thousandsSeparator = Regex("""(?<=\d),(?=\d{3}(?!\d))""")
    private val number = Regex("""\d+(?:\.\d+)?""")
    private val notToTheClick = Regex("""\b(part|partial|top|topup|top-up)\b""")

    fun parse(text: String?): ReceiptEntry? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.lowercase().replace(thousandsSeparator, "")
        val values = number.findAll(cleaned).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (values.isEmpty() || values.size > 2) return null

        val gallons = values.filter { it < MIN_ODOMETER }
        val odometers = values.filter { it >= MIN_ODOMETER }
        if (gallons.size != 1 || odometers.size > 1) return null

        val pump = gallons.single()
        if (pump <= 0.0) return null
        val odometer = odometers.firstOrNull()
        if (odometer != null && odometer > MAX_ODOMETER) return null

        return ReceiptEntry(
            pumpGallons = pump,
            odometerMiles = odometer,
            filledToShutoff = !notToTheClick.containsMatchIn(cleaned),
        )
    }
}
