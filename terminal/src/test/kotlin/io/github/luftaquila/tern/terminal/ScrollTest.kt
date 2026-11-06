// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollTest {
    private fun lines(t: TestTerm, n: Int) {
        for (i in 1..n) t.feed("line$i\r\n")
    }

    @Test
    fun linesScrollIntoHistory() {
        val t = TestTerm(cols = 10, rows = 3, scrollback = 100)
        lines(t, 5)
        assertEquals(listOf("line4", "line5", ""), t.screen())
        assertEquals(listOf("line1", "line2", "line3"), t.history())
        assertEquals(3, t.emu.historySize)
        assertEquals(3L, t.emu.scrolledLines)
        assertEquals("line3", t.line(-1))
    }

    @Test
    fun historyCapacityIsBounded() {
        val t = TestTerm(cols = 10, rows = 3, scrollback = 4)
        lines(t, 20)
        assertEquals(4, t.emu.historySize)
        assertEquals(listOf("line15", "line16", "line17", "line18"), t.history())
        assertEquals(listOf("line19", "line20", ""), t.screen())
        assertEquals(18L, t.emu.scrolledLines)
    }

    @Test
    fun zeroScrollback() {
        val t = TestTerm(cols = 10, rows = 3, scrollback = 0)
        lines(t, 10)
        assertEquals(0, t.emu.historySize)
        assertEquals(listOf("line9", "line10", ""), t.screen())
    }

    @Test
    fun scrollRegionWithTopAtZeroFeedsHistoryAndKeepsBottomFixed() {
        val t = TestTerm(cols = 10, rows = 5)
        t.feed("${CSI}5;1Hstatus")
        t.feed("${CSI}1;4r${CSI}1;1H")
        for (i in 1..6) t.feed("l$i\r\n".let { if (i == 6) "l$i" else it })
        assertEquals(listOf("l3", "l4", "l5", "l6", "status"), t.screen())
        assertEquals(listOf("l1", "l2"), t.history())
    }

    @Test
    fun scrollRegionNotAtTopDoesNotFeedHistory() {
        val t = TestTerm(cols = 10, rows = 5)
        t.feed("top${CSI}2;4r${CSI}2;1H")
        for (i in 1..5) t.feed("r$i" + if (i < 5) "\r\n" else "")
        assertEquals(listOf("top", "r3", "r4", "r5", ""), t.screen())
        assertEquals(0, t.emu.historySize)
    }

    @Test
    fun reverseIndexAtTopMarginScrollsRegionDown() {
        val t = TestTerm(cols = 10, rows = 5)
        t.feed("a\r\nb\r\nc\r\nd\r\ne")
        t.feed("${CSI}2;4r${CSI}2;1H${ESC}M")
        assertEquals(listOf("a", "", "b", "c", "e"), t.screen())
    }

    @Test
    fun lineFeedBelowRegionDoesNotScroll() {
        val t = TestTerm(cols = 10, rows = 5)
        t.feed("${CSI}1;3r${CSI}5;1Hx\n\ny")
        // The cursor is below the region on the last row: LF neither moves nor scrolls.
        assertEquals("xy", t.line(4))
        assertEquals(4, t.emu.cursorRow)
        assertEquals(0, t.emu.historySize)
    }

    @Test
    fun clearScrollbackViaEd3AndApi() {
        val t = TestTerm(cols = 10, rows = 3)
        lines(t, 6)
        assertEquals(4, t.emu.historySize)
        t.feed("${CSI}3J")
        assertEquals(0, t.emu.historySize)
        lines(t, 4)
        assertEquals(4, t.emu.historySize)
        t.emu.clearScrollback()
        assertEquals(0, t.emu.historySize)
        assertEquals(listOf("line3", "line4", ""), t.screen())
    }

    @Test
    fun wrappedLongLineScrollsWithWrapFlag() {
        val t = TestTerm(cols = 4, rows = 2)
        t.feed("abcdefghij")
        assertEquals(listOf("abcd"), t.history())
        assertEquals(listOf("efgh", "ij"), t.screen())
        assertEquals(true, t.emu.getRow(-1).isWrapped)
        assertEquals("abcdefghij", t.emu.getText(-1, 0, 1, 4))
    }

    @Test
    fun manyScrollsKeepRingConsistent() {
        val t = TestTerm(cols = 8, rows = 4, scrollback = 7)
        for (i in 1..1000) t.feed("n$i\r\n")
        assertEquals(listOf("n998", "n999", "n1000", ""), t.screen())
        assertEquals((991..997).map { "n$it" }, t.history())
    }
}
