// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class CursorTest {
    @Test
    fun cupAndDefaults() {
        val t = TestTerm(cols = 10, rows = 5)
        t.feed("${CSI}3;4H")
        assertEquals(2 to 3, t.cursor)
        t.feed("${CSI}H")
        assertEquals(0 to 0, t.cursor)
        t.feed("${CSI};5H")
        assertEquals(0 to 4, t.cursor)
        t.feed("${CSI}99;99f")
        assertEquals(4 to 9, t.cursor)
        t.feed("${CSI}0;0H")
        assertEquals(0 to 0, t.cursor)
    }

    @Test
    fun relativeMovesClamp() {
        val t = TestTerm(cols = 10, rows = 5).feed("${CSI}3;3H")
        t.feed("${CSI}A")
        assertEquals(1 to 2, t.cursor)
        t.feed("${CSI}10A")
        assertEquals(0 to 2, t.cursor)
        t.feed("${CSI}10B")
        assertEquals(4 to 2, t.cursor)
        t.feed("${CSI}0C")
        assertEquals(4 to 3, t.cursor)
        t.feed("${CSI}20C")
        assertEquals(4 to 9, t.cursor)
        t.feed("${CSI}3D")
        assertEquals(4 to 6, t.cursor)
        t.feed("${CSI}20D")
        assertEquals(4 to 0, t.cursor)
        t.feed("${CSI}2a")
        assertEquals(4 to 2, t.cursor)
        t.feed("${CSI}2F")
        assertEquals(2 to 0, t.cursor)
        t.feed("${CSI}5;5H${CSI}E")
        assertEquals(4 to 0, t.cursor)
    }

    @Test
    fun chaHpaVpaVpr() {
        val t = TestTerm(cols = 10, rows = 5).feed("${CSI}7G")
        assertEquals(0 to 6, t.cursor)
        t.feed("${CSI}3`")
        assertEquals(0 to 2, t.cursor)
        t.feed("${CSI}4d")
        assertEquals(3 to 2, t.cursor)
        t.feed("${CSI}1d${CSI}2e")
        assertEquals(2 to 2, t.cursor)
    }

    @Test
    fun cursorUpDownStopAtMargins() {
        val t = TestTerm(cols = 10, rows = 10).feed("${CSI}3;6r") // rows 2..5
        t.feed("${CSI}5;1H${CSI}10A")
        assertEquals(2 to 0, t.cursor)
        t.feed("${CSI}10B")
        assertEquals(5 to 0, t.cursor)
        // outside the region the margins do not apply
        t.feed("${CSI}1;1H${CSI}A")
        assertEquals(0 to 0, t.cursor)
        t.feed("${CSI}8;1H${CSI}10B")
        assertEquals(9 to 0, t.cursor)
    }

    @Test
    fun originMode() {
        val t = TestTerm(cols = 10, rows = 10).feed("${CSI}3;6r${CSI}?6h")
        assertEquals(2 to 0, t.cursor)
        t.feed("${CSI}2;3H")
        assertEquals(3 to 2, t.cursor)
        t.feed("${CSI}20;1H")
        assertEquals(5 to 0, t.cursor)
        t.replies()
        t.feed("${CSI}2;3H${CSI}6n")
        assertEquals("${CSI}2;3R", t.replies())
        t.feed("${CSI}?6l")
        assertEquals(0 to 0, t.cursor)
    }

    @Test
    fun tabs() {
        val t = TestTerm(cols = 30).feed("\ta\tb")
        assertEquals(0 to 17, t.cursor)
        assertEquals("        a       b", t.line(0))
        t.feed("${CSI}3g\r${CSI}5G${ESC}H\r\tX")
        assertEquals(0 to 5, t.cursor)
        t.feed("\t")
        assertEquals(0 to 29, t.cursor)
    }

    @Test
    fun tabClearSingleAndCbtCht() {
        val t = TestTerm(cols = 40)
        t.feed("${CSI}9G${CSI}0g\r\t")
        assertEquals(0 to 16, t.cursor)
        t.feed("${CSI}2I")
        assertEquals(0 to 32, t.cursor)
        t.feed("${CSI}Z")
        assertEquals(0 to 24, t.cursor)
        t.feed("${CSI}5Z")
        assertEquals(0 to 0, t.cursor)
    }

    @Test
    fun backspaceAtColumnZeroStays() {
        val t = TestTerm().feed("\b\b")
        assertEquals(0 to 0, t.cursor)
    }

    @Test
    fun lineFeedAndNewLineMode() {
        val t = TestTerm().feed("ab\ncd")
        assertEquals(listOf("ab", "  cd"), t.screen().take(2))
        t.feed("${CSI}20h\nx")
        assertEquals("x", t.line(2))
        assertEquals("\r\n", String(t.emu.encodeKey(TerminalKey.ENTER)))
    }

    @Test
    fun indexNextLineReverseIndex() {
        val t = TestTerm(rows = 3).feed("a${ESC}Db${ESC}Ec")
        assertEquals(listOf("a", " b", "c"), t.screen())
        t.feed("${CSI}H${ESC}M")
        assertEquals(listOf("", "a", " b"), t.screen())
    }

    @Test
    fun saveRestoreCursorWithAttributesAndCharset() {
        val t = TestTerm().feed("${CSI}2;3H${CSI}1m${ESC}(0${ESC}7")
        t.feed("${CSI}H${CSI}0m${ESC}(B")
        t.feed("${ESC}8q")
        assertEquals(1 to 3, t.cursor)
        assertEquals("  ─", t.line(1))
        assertEquals(TextStyle.BOLD, TextStyle.flags(t.style(1, 2)) and TextStyle.BOLD)
    }

    @Test
    fun restoreWithoutSaveGoesHome() {
        val t = TestTerm().feed("${CSI}3;3H${CSI}1m${ESC}8x")
        assertEquals(0 to 1, t.cursor)
        assertEquals(TextStyle.DEFAULT, t.style(0, 0))
    }

    @Test
    fun scoscScorc() {
        val t = TestTerm().feed("${CSI}2;2H${CSI}s${CSI}4;4H${CSI}u")
        assertEquals(1 to 1, t.cursor)
    }

    @Test
    fun cursorVisibilityShapeBlink() {
        val t = TestTerm().feed("${CSI}?25l")
        assertEquals(false, t.emu.isCursorVisible)
        t.feed("${CSI}?25h${CSI}6 q")
        assertEquals(true, t.emu.isCursorVisible)
        assertEquals(CursorShape.BAR, t.emu.cursorShape)
        assertEquals(false, t.emu.isCursorBlinking)
        t.feed("${CSI}3 q")
        assertEquals(CursorShape.UNDERLINE, t.emu.cursorShape)
        assertEquals(true, t.emu.isCursorBlinking)
        t.feed("${CSI}?12l")
        assertEquals(false, t.emu.isCursorBlinking)
        t.feed("${CSI}0 q")
        assertEquals(CursorShape.BLOCK, t.emu.cursorShape)
    }
}
