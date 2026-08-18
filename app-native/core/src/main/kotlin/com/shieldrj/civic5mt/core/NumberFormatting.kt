package com.shieldrj.civic5mt.core

import java.math.BigDecimal
import java.util.Locale

/**
 * JavaScript number formatting, reproduced deliberately.
 *
 * The readings these produce are pinned by assertions carried over from the TypeScript
 * build ("99.22 %", "-26.5 Pa"), and they are also what a driver reads off the screen. Two
 * differences between the platforms would quietly change those strings:
 *
 * - `String.format` is locale-sensitive, so on a phone set to most of Europe "26.5" becomes
 *   "26,5". Every call here pins [Locale.ROOT].
 * - Kotlin renders a whole Double as "0.0" where JavaScript renders "0". The TypeScript ran
 *   its rounded value back through `parseFloat`, which drops the trailing zeros, so
 *   matching it means stripping them explicitly.
 */

/** `Number.prototype.toFixed` - fixed decimal places, half-up, locale-independent. */
fun toFixed(value: Double, digits: Int): String =
    String.format(Locale.ROOT, "%.${digits}f", value)

/**
 * `String(number)` for the values this app formats: no trailing zeros, no exponent, and
 * no negative zero. Not a general JavaScript number serialiser - it is only ever handed a
 * value already rounded to two decimals or to a whole number.
 */
fun jsNumberToString(value: Double): String {
    if (!value.isFinite()) return value.toString()
    val stripped = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    return if (stripped == "-0") "0" else stripped
}
