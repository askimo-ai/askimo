/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.text.NumberFormat

object NumberFormatUtil {
    /**
     * Formats a percentage value (0–100 range) using the system locale.
     *
     * Uses [NumberFormat] so the decimal separator matches the user's locale
     * (e.g. "35.3800" in en-US, "35,3800" in de-DE).
     *
     * The default of 4 fraction digits is intentional: when indexing 50+ files with a
     * large file (e.g. 5 MB → ~1 316 chunks), each 5-chunk emit advances the counter by
     * only ~0.0038 %, which would round to "0.00 %" with 2 digits and appear frozen.
     * 4 digits keeps every emit visible regardless of project size.
     *
     * @param value          Percentage in 0–100 range (e.g. 35.38).
     * @param fractionDigits Number of decimal places (default 4).
     * @return Locale-formatted string, e.g. "35.3800" or "35,3800".
     */
    fun formatPercent(value: Float, fractionDigits: Int = 4): String = NumberFormat.getInstance().apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }.format(value)

    /**
     * Formats a decimal number using the system locale.
     * Used for settings fields (e.g. min-score thresholds) that need locale-aware
     * display but accept plain numeric input while being edited.
     *
     * @param value          The value to format.
     * @param fractionDigits Minimum / maximum decimal places (default 1).
     * @return Locale-formatted string, e.g. "0.3" (en-US) or "0,3" (de-DE).
     */
    fun formatDecimal(value: Double, fractionDigits: Int = 1): String = NumberFormat.getInstance().apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = maxOf(fractionDigits, value.toBigDecimal().scale())
    }.format(value)
}
