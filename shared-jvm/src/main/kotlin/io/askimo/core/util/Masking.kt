/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

enum class LengthDisclosure { HIDE, APPROX, EXACT }

object Masking {
    /**
     * Mask sensitive values for display/logging without revealing exact length.
     * - By default (HIDE), shows fixed bullet block; no length info is leaked.
     * - APPROX adds a coarse bucket (≤8, 9–16, 17–32, ≥33).
     * - EXACT is available but NOT recommended; off by default.
     */
    @JvmStatic
    fun maskSecret(
        value: Any?,
        revealPrefix: Int = 3,
        revealSuffix: Int = 4,
        maskChar: Char = '•',
        // fixed-size masked middle (prevents length inference)
        maskBlock: Int = 6,
        disclosure: LengthDisclosure = LengthDisclosure.HIDE,
        emptyPlaceholder: String = "<empty>",
    ): String {
        val s = value?.toString() ?: ""
        if (s.isEmpty()) return emptyPlaceholder

        val len = s.length

        // Compute prefix/suffix safely
        val prefixLen = revealPrefix.coerceAtMost(len)
        val suffixLen = revealSuffix.coerceAtMost((len - prefixLen).coerceAtLeast(0))

        // If too short to safely reveal prefix/suffix, mask fully with a fixed block.
        if (len <= (revealPrefix + revealSuffix)) {
            val block = maskChar.toString().repeat(maskBlock)
            return when (disclosure) {
                LengthDisclosure.HIDE -> block
                LengthDisclosure.APPROX -> "$block ${approx(len)}"
                LengthDisclosure.EXACT -> "$block ($len chars)"
            }
        }

        val prefix = s.take(prefixLen)
        val suffix = s.takeLast(suffixLen)
        val maskedMid = maskChar.toString().repeat(maskBlock) // constant size, not length-dependent
        val base = "$prefix$maskedMid$suffix"

        return when (disclosure) {
            LengthDisclosure.HIDE -> base
            LengthDisclosure.APPROX -> "$base ${approx(len)}"
            LengthDisclosure.EXACT -> "$base ($len chars)"
        }
    }

    private fun approx(len: Int): String = when {
        len <= 8 -> "(≤8)"
        len <= 16 -> "(9–16)"
        len <= 32 -> "(17–32)"
        else -> "(≥33)"
    }
}
