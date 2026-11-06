// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

/**
 * Column width of a code point, matching glibc's wcwidth() (what remote programs use):
 * 0 for combining / zero-width / Hangul conjoining medial+final jamo, 2 for East Asian
 * Wide/Fullwidth (Hangul syllables, CJK, most emoji), 1 otherwise. C0/C1 controls: 0.
 *
 * Tables are generated from the Unicode 16.0 UCD by terminal/tools/gen_wcwidth.py.
 */
object WcWidth {
    fun width(codePoint: Int): Int {
        if (codePoint < 0x20) return 0
        if (codePoint < 0x7F) return 1
        if (codePoint < 0xA0) return 0
        if (codePoint < 0x0300) return 1
        // Hangul syllables are by far the most common non-ASCII text for our users.
        if (codePoint in 0xAC00..0xD7A3) return 2
        if (codePoint > 0x10FFFF) return 1
        if (inTable(WcWidthTables.ZERO_WIDTH, codePoint)) return 0
        if (inTable(WcWidthTables.WIDE, codePoint)) return 2
        return 1
    }

    /** Binary search over sorted inclusive [start, end] pairs. */
    private fun inTable(table: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = table.size / 2 - 1
        if (cp < table[0] || cp > table[table.size - 1]) return false
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = table[mid * 2]
            val end = table[mid * 2 + 1]
            if (cp < start) {
                hi = mid - 1
            } else if (cp > end) {
                lo = mid + 1
            } else {
                return true
            }
        }
        return false
    }
}
