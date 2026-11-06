// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class WcWidthTest {
    private fun w(cp: Int) = WcWidth.width(cp)

    @Test
    fun asciiAndControls() {
        assertEquals(1, w('a'.code))
        assertEquals(1, w(' '.code))
        assertEquals(1, w('~'.code))
        assertEquals(0, w(0))
        assertEquals(0, w(0x1B))
        assertEquals(0, w(0x7F))
        assertEquals(0, w(0x85))
        assertEquals(1, w(0xA0))
        assertEquals(1, w(0xAD)) // soft hyphen is visible in terminals
    }

    @Test
    fun hangul() {
        assertEquals(2, w('가'.code))
        assertEquals(2, w('힣'.code))
        assertEquals(2, w('ㄱ'.code)) // compatibility jamo
        assertEquals(2, w('ㅏ'.code))
        assertEquals(2, w(0x1100)) // choseong
        assertEquals(2, w(0x115F))
        assertEquals(0, w(0x1160)) // jungseong filler
        assertEquals(0, w(0x1161))
        assertEquals(0, w(0x11A8)) // jongseong
        assertEquals(0, w(0x11FF))
        assertEquals(2, w(0xA960)) // extended-A choseong
        assertEquals(0, w(0xD7B0)) // extended-B jungseong
        assertEquals(0, w(0xD7FB))
        assertEquals(1, w(0xFFA0)) // halfwidth hangul filler
        assertEquals(1, w(0xFFA1)) // halfwidth ㄱ
    }

    @Test
    fun cjkAndFullwidth() {
        assertEquals(2, w('漢'.code))
        assertEquals(2, w('か'.code))
        assertEquals(2, w('カ'.code))
        assertEquals(1, w('ｶ'.code)) // halfwidth katakana
        assertEquals(2, w('Ａ'.code))
        assertEquals(2, w(0x3000)) // ideographic space
        assertEquals(2, w(0x4DBF))
        assertEquals(2, w(0x2FFFD)) // plane 2 default W
        assertEquals(2, w(0x3FFFD))
        assertEquals(0, w(0x302A)) // ideographic tone mark: Mn wins over W
        assertEquals(0, w(0x3099))
    }

    @Test
    fun emojiAndSymbols() {
        assertEquals(2, w(0x1F642))
        assertEquals(2, w(0x1F44D))
        assertEquals(2, w(0x2705))
        assertEquals(1, w(0x2764)) // heavy black heart is narrow by default
        assertEquals(0, w(0xFE0F))
        assertEquals(0, w(0x200D))
        assertEquals(0, w(0x200B))
        assertEquals(1, w(0x2500))
        assertEquals(1, w(0x2588))
        assertEquals(1, w(0x2460)) // ambiguous -> narrow
    }

    @Test
    fun combiningMarks() {
        assertEquals(0, w(0x0300))
        assertEquals(0, w(0x0301))
        assertEquals(0, w(0x20DD)) // Me
        assertEquals(0, w(0xE0001)) // tag (Cf)
        assertEquals(1, w(0x0627)) // arabic letter
    }

    @Test
    fun outOfRange() {
        assertEquals(1, w(0x110000))
        assertEquals(1, w(0x10FFFF))
    }
}
