// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StretchToFillTest {

    @Test
    fun keysThatFitFillTheRowExactly() {
        val natural = listOf(44, 58, 44)
        val widths = stretchToFill(natural, available = 300, limit = 110)
        assertEquals(300, widths.sum())
        // Every key grows by the same amount, give or take the rounding remainder.
        val growth = widths.zip(natural) { w, n -> w - n }
        assertTrue(growth.max() - growth.min() <= 1, "growth $growth")
    }

    @Test
    fun growthStopsAtTheLimit() {
        val widths = stretchToFill(listOf(44, 44), available = 400, limit = 110)
        assertEquals(listOf(110, 110), widths, "a short row stays short (it is centered)")
    }

    @Test
    fun keysAtTheLimitLeaveTheRestToOthers() {
        // The wide key starts near the limit; the narrow ones take the remaining space.
        val widths = stretchToFill(listOf(100, 44, 44), available = 300, limit = 110)
        assertEquals(listOf(110, 95, 95), widths)
        assertEquals(300, widths.sum())
    }

    @Test
    fun keysWiderThanTheLimitKeepTheirWidth() {
        assertEquals(listOf(150, 110), stretchToFill(listOf(150, 44), available = 300, limit = 110))
    }

    @Test
    fun overflowingRowsAreLeftAlone() {
        assertEquals(listOf(44, 44, 44), stretchToFill(listOf(44, 44, 44), available = 100, limit = 110))
        assertEquals(emptyList(), stretchToFill(emptyList(), available = 100, limit = 110))
    }
}
