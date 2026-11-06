// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.input

/**
 * Maps Hangul jamo to the QWERTY key that produces them on the standard Korean 2-set
 * (두벌식, KS X 5002) layout, so CTRL/ALT combos work without switching the keyboard to
 * English: CTRL + ㅊ → Ctrl-C, ALT + ㅠ → Alt-b, ...
 */
object HangulKeymap {
    private val map: Map<Int, Char> = buildMap {
        fun put(jamo: Char, key: Char) = put(jamo.code, key)
        // Top row
        put('ㅂ', 'q'); put('ㅈ', 'w'); put('ㄷ', 'e'); put('ㄱ', 'r'); put('ㅅ', 't')
        put('ㅛ', 'y'); put('ㅕ', 'u'); put('ㅑ', 'i'); put('ㅐ', 'o'); put('ㅔ', 'p')
        // Home row
        put('ㅁ', 'a'); put('ㄴ', 's'); put('ㅇ', 'd'); put('ㄹ', 'f'); put('ㅎ', 'g')
        put('ㅗ', 'h'); put('ㅓ', 'j'); put('ㅏ', 'k'); put('ㅣ', 'l')
        // Bottom row
        put('ㅋ', 'z'); put('ㅌ', 'x'); put('ㅊ', 'c'); put('ㅍ', 'v'); put('ㅠ', 'b')
        put('ㅜ', 'n'); put('ㅡ', 'm')
        // Shifted
        put('ㅃ', 'Q'); put('ㅉ', 'W'); put('ㄸ', 'E'); put('ㄲ', 'R'); put('ㅆ', 'T')
        put('ㅒ', 'O'); put('ㅖ', 'P')
    }

    /** Initial consonants of precomposed syllables (U+AC00 + (L*21 + V)*28 + T), in order. */
    private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    /**
     * The QWERTY key for [codePoint] if it is a Hangul compatibility jamo, or for the initial
     * consonant of a precomposed syllable (a keyboard may already have formed one); null otherwise.
     */
    /** Hangul syllables and jamo (compatibility, conjoining and extended). */
    fun isHangul(c: Char): Boolean =
        c in '\uAC00'..'\uD7A3' || c in '\u3131'..'\u318E' || c in '\u1100'..'\u11FF' ||
            c in '\uA960'..'\uA97F' || c in '\uD7B0'..'\uD7FF'

    fun toLatin(codePoint: Int): Char? {
        map[codePoint]?.let { return it }
        if (codePoint in 0xAC00..0xD7A3) {
            val initial = INITIALS[(codePoint - 0xAC00) / (21 * 28)]
            return map[initial.code]
        }
        return null
    }
}
