// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResizeTest {
    @Test
    fun shrinkRowsDropsBlankBottomRowsFirst() {
        val t = TestTerm(cols = 10, rows = 5).feed("a\r\nb")
        t.emu.resize(10, 3)
        assertEquals(listOf("a", "b", ""), t.screen())
        assertEquals(1 to 1, t.cursor)
        assertEquals(0, t.emu.historySize)
    }

    @Test
    fun shrinkRowsPushesTopIntoHistoryKeepingCursorVisible() {
        val t = TestTerm(cols = 10, rows = 5).feed("a\r\nb\r\nc\r\nd\r\ne")
        t.emu.resize(10, 3)
        assertEquals(listOf("c", "d", "e"), t.screen())
        assertEquals(listOf("a", "b"), t.history())
        assertEquals(2 to 1, t.cursor)
        assertEquals(2L, t.emu.scrolledLines)
    }

    @Test
    fun growRowsPullsBackHistory() {
        val t = TestTerm(cols = 10, rows = 3).feed("a\r\nb\r\nc\r\nd\r\ne")
        assertEquals(listOf("c", "d", "e"), t.screen())
        t.emu.resize(10, 4)
        assertEquals(listOf("b", "c", "d", "e"), t.screen())
        assertEquals(3 to 1, t.cursor)
        t.emu.resize(10, 7)
        assertEquals(listOf("a", "b", "c", "d", "e", "", ""), t.screen())
        assertEquals(4 to 1, t.cursor)
        assertEquals(0, t.emu.historySize)
        assertEquals(0L, t.emu.scrolledLines)
    }

    @Test
    fun keyboardToggleRoundTripIsLossless() {
        val t = TestTerm(cols = 10, rows = 6)
        for (i in 1..10) t.feed("L$i\r\n")
        t.feed("$ ")
        val screen = t.screen()
        val history = t.history()
        val cursor = t.cursor
        t.emu.resize(10, 3)
        assertEquals(2 to 2, t.cursor)
        t.emu.resize(10, 6)
        assertEquals(screen, t.screen())
        assertEquals(history, t.history())
        assertEquals(cursor, t.cursor)
    }

    @Test
    fun reflowNarrowerWrapsLines() {
        val t = TestTerm(cols = 10, rows = 4).feed("0123456789\r\nabc")
        t.emu.resize(5, 4)
        assertEquals(listOf("01234", "56789", "abc", ""), t.screen())
        assertTrue(t.emu.getRow(0).isWrapped)
        assertFalse(t.emu.getRow(1).isWrapped)
        assertEquals(2 to 3, t.cursor)
    }

    @Test
    fun reflowWiderJoinsWrappedLines() {
        val t = TestTerm(cols = 5, rows = 4).feed("0123456789ab\r\nxyz")
        assertEquals(listOf("01234", "56789", "ab", "xyz"), t.screen())
        t.emu.resize(12, 4)
        assertEquals(listOf("0123456789ab", "xyz", "", ""), t.screen())
        assertEquals(1 to 3, t.cursor)
    }

    @Test
    fun reflowMovesOverflowIntoHistory() {
        val t = TestTerm(cols = 8, rows = 3, scrollback = 100).feed("aaaaaaaa\r\nbbbbbbbb\r\n$ ")
        t.emu.resize(4, 3)
        assertEquals(listOf("bbbb", "bbbb", "$"), t.screen())
        assertEquals(listOf("aaaa", "aaaa"), t.history())
        assertEquals(2 to 2, t.cursor)
    }

    @Test
    fun reflowKeepsCursorOnSameCharacterInMiddleOfLine() {
        val t = TestTerm(cols = 10, rows = 3).feed("abcdefghij${CSI}1;8H")
        // cursor on 'h' (col 7)
        t.emu.resize(4, 3)
        val (r, c) = t.cursor
        assertEquals('h'.code, t.emu.getRow(r).getCodePoint(c))
    }

    @Test
    fun reflowWithWideChars() {
        val t = TestTerm(cols = 6, rows = 4).feed("가나다라마")
        assertEquals(listOf("가나다", "라마"), t.screen().take(2))
        t.emu.resize(5, 4)
        assertEquals(listOf("가나", "다라", "마", ""), t.screen())
        t.emu.resize(10, 4)
        assertEquals(listOf("가나다라마", "", "", ""), t.screen())
        assertEquals(0 to 9, t.cursor)
    }

    @Test
    fun reflowDropsWideCharSkipCell() {
        // 5 cols: "abcd" + 한 forced to wrap leaves an empty cell at col 4.
        val t = TestTerm(cols = 5, rows = 3).feed("abcd한x")
        assertEquals(listOf("abcd", "한x", ""), t.screen())
        t.emu.resize(10, 3)
        assertEquals("abcd한x", t.line(0))
    }

    @Test
    fun reflowPreservesStylesAndCombining() {
        val t = TestTerm(cols = 4, rows = 3).feed("ab${CSI}1mćd${CSI}0mef")
        t.emu.resize(8, 3)
        assertEquals("abćdef", t.line(0))
        assertEquals(TextStyle.BOLD, TextStyle.flags(t.style(0, 2)) and TextStyle.BOLD)
        assertEquals("́", t.emu.getRow(0).getCombining(2))
    }

    @Test
    fun reflowPendingWrapCursor() {
        val t = TestTerm(cols = 5, rows = 3).feed("abcde")
        t.emu.resize(10, 3)
        assertEquals(0 to 5, t.cursor)
        t.feed("f")
        assertEquals("abcdef", t.line(0))
    }

    @Test
    fun alternateScreenResizeTruncatesAndMainReflows() {
        val t = TestTerm(cols = 10, rows = 4).feed("0123456789\r\n$ ")
        t.feed("${CSI}?1049h${CSI}Hfull-screen")
        t.emu.resize(5, 4)
        assertEquals("full-", t.line(0))
        t.feed("${CSI}?1049l")
        assertEquals(listOf("01234", "56789", "$", ""), t.screen())
        assertEquals(2 to 2, t.cursor)
    }

    @Test
    fun resizeResetsMarginsAndClampsCursor() {
        val t = TestTerm(cols = 10, rows = 10).feed("${CSI}2;5r${CSI}10;10H")
        t.emu.resize(5, 5)
        assertTrue(t.emu.cursorRow < 5)
        assertTrue(t.emu.cursorCol < 5)
        t.feed("${CSI}5;1H\n")
        // margins were reset: LF at last row scrolls
        assertEquals(4, t.emu.cursorRow)
    }

    @Test
    fun minimumSizeIsClamped() {
        val t = TestTerm(cols = 0, rows = 0)
        assertEquals(2, t.emu.columns)
        assertEquals(1, t.emu.rows)
        t.feed("한글abc")
        t.emu.resize(1, 1)
        assertEquals(2, t.emu.columns)
    }

    @Test
    fun tabStopsExtendOnGrow() {
        val t = TestTerm(cols = 10, rows = 2)
        t.emu.resize(30, 2)
        t.feed("\t\t\t")
        assertEquals(0 to 24, t.cursor)
    }
}
