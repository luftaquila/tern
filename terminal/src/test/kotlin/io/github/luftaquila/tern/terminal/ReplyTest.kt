// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplyTest {
    @Test
    fun deviceAttributes() {
        val t = TestTerm()
        t.feed("${CSI}c")
        assertEquals("${CSI}?62;22c", t.replies())
        t.feed("${CSI}0c")
        assertEquals("${CSI}?62;22c", t.replies())
        t.feed("${CSI}>c")
        assertEquals("${CSI}>1;10;0c", t.replies())
        t.feed("${CSI}>0c")
        assertEquals("${CSI}>1;10;0c", t.replies())
        t.feed("${CSI}=c${CSI}1c")
        assertEquals("", t.replies())
        t.feed("${ESC}Z")
        assertEquals("${CSI}?62;22c", t.replies())
    }

    @Test
    fun statusAndCursorPositionReports() {
        val t = TestTerm(cols = 20, rows = 5)
        t.feed("${CSI}5n")
        assertEquals("${CSI}0n", t.replies())
        t.feed("${CSI}3;7H${CSI}6n")
        assertEquals("${CSI}3;7R", t.replies())
        t.feed("${CSI}?6n")
        assertEquals("${CSI}?3;7R", t.replies())
    }

    @Test
    fun cprAtPendingWrapReportsLastColumn() {
        val t = TestTerm(cols = 5, rows = 3).feed("abcde${CSI}6n")
        assertEquals("${CSI}1;5R", t.replies())
    }

    @Test
    fun windowSizeReport() {
        val t = TestTerm(cols = 33, rows = 11).feed("${CSI}18t${CSI}19t${CSI}14t")
        assertEquals("${CSI}8;11;33t${CSI}9;11;33t", t.replies())
    }

    @Test
    fun xtversion() {
        val t = TestTerm().feed("${CSI}>q")
        assertTrue(Regex("\u001bP>\\|[^\u001b]+\u001b\\\\").matches(t.replies()), t.replies())
    }

    @Test
    fun decrqmPrivateModes() {
        val t = TestTerm()
        t.feed("${CSI}?2004\$p")
        assertEquals("${CSI}?2004;2\$y", t.replies())
        t.feed("${CSI}?2004h${CSI}?2004\$p")
        assertEquals("${CSI}?2004;1\$y", t.replies())
        t.feed("${CSI}?7\$p")
        assertEquals("${CSI}?7;1\$y", t.replies())
        t.feed("${CSI}?1049h${CSI}?1049\$p")
        assertEquals("${CSI}?1049;1\$y", t.replies())
        t.feed("${CSI}?2026\$p")
        assertEquals("${CSI}?2026;0\$y", t.replies())
    }

    @Test
    fun decrqmAnsiModes() {
        val t = TestTerm()
        t.feed("${CSI}4\$p")
        assertEquals("${CSI}4;2\$y", t.replies())
        t.feed("${CSI}4h${CSI}4\$p")
        assertEquals("${CSI}4;1\$y", t.replies())
        t.feed("${CSI}99\$p")
        assertEquals("${CSI}99;0\$y", t.replies())
    }

    @Test
    fun decrqss() {
        val t = TestTerm(rows = 10)
        t.feed("${ESC}P\$qm$ST")
        assertEquals("${ESC}P1\$r0m$ST", t.replies())
        t.feed("${CSI}1;4;31;48;5;100m${ESC}P\$qm$ST")
        assertEquals("${ESC}P1\$r0;1;4;31;48;5;100m$ST", t.replies())
        t.feed("${CSI}38;2;1;2;3m${ESC}P\$qm$ST")
        assertEquals("${ESC}P1\$r0;1;4;38;2;1;2;3;48;5;100m$ST", t.replies())
        t.feed("${CSI}2;8r${ESC}P\$qr$ST")
        assertEquals("${ESC}P1\$r2;8r$ST", t.replies())
        t.feed("${CSI}4 q${ESC}P\$q q$ST")
        assertEquals("${ESC}P1\$r4 q$ST", t.replies())
        t.feed("${ESC}P\$qz$ST")
        assertEquals("${ESC}P0\$r$ST", t.replies())
    }

    @Test
    fun oscColorQueriesUseRequestTerminator() {
        val scheme = ColorSchemes.DEFAULT
        val t = TestTerm()
        t.feed("${OSC}11;?\u0007")
        val bg = scheme.background
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        fun h(v: Int) = String.format("%04x", v * 257)
        assertEquals("${OSC}11;rgb:${h(r)}/${h(g)}/${h(b)}\u0007", t.replies())
        t.feed("${OSC}10;?$ST")
        val fg = scheme.foreground
        assertEquals(
            "${OSC}10;rgb:${h((fg shr 16) and 0xFF)}/${h((fg shr 8) and 0xFF)}/${h(fg and 0xFF)}$ST",
            t.replies(),
        )
        t.feed("${OSC}4;1;?$ST")
        val c1 = scheme.ansi[1]
        assertEquals(
            "${OSC}4;1;rgb:${h((c1 shr 16) and 0xFF)}/${h((c1 shr 8) and 0xFF)}/${h(c1 and 0xFF)}$ST",
            t.replies(),
        )
    }

    @Test
    fun multipleDynamicColorQueries() {
        val t = TestTerm()
        t.feed("${OSC}10;?;?\u0007")
        val replies = t.replies()
        assertEquals(2, Regex("\u001b\\]1[01];rgb:").findAll(replies).count())
    }
}
