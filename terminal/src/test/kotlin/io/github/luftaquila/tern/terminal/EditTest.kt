// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditTest {
    private fun filled(cols: Int = 10, rows: Int = 5): TestTerm {
        val t = TestTerm(cols = cols, rows = rows, scrollback = 50)
        for (r in 0 until rows) {
            t.feed("${CSI}${r + 1};1H")
            t.feed(('a' + r).toString().repeat(cols))
        }
        return t
    }

    @Test
    fun eraseInDisplay() {
        val t = filled().feed("${CSI}3;4H${CSI}J")
        assertEquals(listOf("aaaaaaaaaa", "bbbbbbbbbb", "ccc", "", ""), t.screen())
        val t1 = filled().feed("${CSI}3;4H${CSI}1J")
        assertEquals(listOf("", "", "    cccccc", "dddddddddd", "eeeeeeeeee"), t1.screen())
        val t2 = filled().feed("${CSI}3;4H${CSI}2J")
        assertEquals(listOf("", "", "", "", ""), t2.screen())
        assertEquals(2 to 3, t2.cursor)
    }

    @Test
    fun eraseInLine() {
        val t = filled().feed("${CSI}2;5H${CSI}K")
        assertEquals("bbbb", t.line(1))
        t.feed("${CSI}3;5H${CSI}1K")
        assertEquals("     ccccc", t.line(2))
        t.feed("${CSI}4;5H${CSI}2K")
        assertEquals("", t.line(3))
    }

    @Test
    fun eraseUsesCurrentBackground() {
        val t = TestTerm(cols = 5).feed("${CSI}44m${CSI}2K${CSI}0m")
        val c = t.colors(0, 3)
        assertEquals(ColorSchemes.DEFAULT.ansi[4], c[1])
        assertEquals(0, t.emu.getRow(0).getCodePoint(3))
        assertEquals(0, TextStyle.flags(t.style(0, 3)))
    }

    @Test
    fun eraseCharacters() {
        val t = filled().feed("${CSI}1;3H${CSI}4X")
        assertEquals("aa    aaaa", t.line(0))
        assertEquals(0 to 2, t.cursor)
        t.feed("${CSI}100X")
        assertEquals("aa", t.line(0))
    }

    @Test
    fun insertAndDeleteCharacters() {
        val t = TestTerm(cols = 8).feed("abcdefgh${CSI}3G${CSI}2@")
        assertEquals("ab  cdef", t.line(0))
        t.feed("${CSI}3P")
        assertEquals("abdef", t.line(0))
        t.feed("${CSI}1G${CSI}100P")
        assertEquals("", t.line(0))
    }

    @Test
    fun insertCharsSplittingWideCharAtEdge() {
        // "abcde한" in 8 cols: 한 at cols 5-6; inserting 2 at col 0 pushes 한 to cols 7-8 -> cut.
        val t = TestTerm(cols = 8).feed("abcde한${CSI}1G${CSI}2@")
        assertEquals("  abcde", t.line(0))
        assertEquals(0, t.emu.getRow(0).getCodePoint(7))
    }

    @Test
    fun deleteCharsOnWideCharHalves() {
        val t = TestTerm(cols = 10).feed("a한b${CSI}3G${CSI}P") // cursor on right half of 한
        assertEquals("a b", t.line(0))
        val t2 = TestTerm(cols = 10).feed("a한b${CSI}2G${CSI}P") // delete left half
        assertEquals("a b", t2.line(0))
        assertEquals(0, t2.emu.getRow(0).getCodePoint(1))
    }

    @Test
    fun eraseHalfOfWideCharErasesBoth() {
        // EL 1 ending on the right half of 한 erases cols 0..2 (both halves).
        val t = TestTerm(cols = 10).feed("a한b${CSI}3G${CSI}1K")
        assertEquals("   b", t.line(0))
        // ECH 1 on the left half of 한 erases its right half too.
        val t2 = TestTerm(cols = 10).feed("a한b${CSI}2G${CSI}1X")
        assertEquals("a  b", t2.line(0))
    }

    @Test
    fun insertAndDeleteLinesInsideRegion() {
        val t = filled().feed("${CSI}2;4r${CSI}3;5H${CSI}L")
        assertEquals(listOf("aaaaaaaaaa", "bbbbbbbbbb", "", "cccccccccc", "eeeeeeeeee"), t.screen())
        assertEquals(2 to 0, t.cursor)
        t.feed("${CSI}2;1H${CSI}2M")
        assertEquals(listOf("aaaaaaaaaa", "cccccccccc", "", "", "eeeeeeeeee"), t.screen())
    }

    @Test
    fun insertLinesOutsideRegionIgnored() {
        val t = filled().feed("${CSI}2;4r${CSI}5;1H${CSI}L")
        assertEquals("eeeeeeeeee", t.line(4))
        assertEquals("dddddddddd", t.line(3))
    }

    @Test
    fun deleteLinesDoesNotFeedScrollback() {
        val t = filled().feed("${CSI}1;1H${CSI}2M")
        assertEquals(0, t.emu.historySize)
        assertEquals(listOf("cccccccccc", "dddddddddd", "eeeeeeeeee", "", ""), t.screen())
    }

    @Test
    fun scrollUpAndDown() {
        val t = filled().feed("${CSI}2S")
        assertEquals(listOf("cccccccccc", "dddddddddd", "eeeeeeeeee", "", ""), t.screen())
        assertEquals(listOf("aaaaaaaaaa", "bbbbbbbbbb"), t.history())
        t.feed("${CSI}T")
        assertEquals(listOf("", "cccccccccc", "dddddddddd", "eeeeeeeeee", ""), t.screen())
    }

    @Test
    fun scrollDownWithManyParamsIsIgnored() {
        // CSI Ps;Ps;Ps;Ps;Ps T is xterm's mouse highlight tracking, not SD.
        val t = filled().feed("${CSI}1;1;1;1;1T")
        assertEquals("aaaaaaaaaa", t.line(0))
    }

    @Test
    fun decaln() {
        val t = TestTerm(cols = 4, rows = 2).feed("${CSI}2;2r${ESC}#8")
        assertEquals(listOf("EEEE", "EEEE"), t.screen())
        assertEquals(0 to 0, t.cursor)
    }

    @Test
    fun eraseLineClearsWrapFlag() {
        val t = TestTerm(cols = 4).feed("abcdef")
        assertTrue(t.emu.getRow(0).isWrapped)
        t.feed("${CSI}1;2H${CSI}K")
        assertFalse(t.emu.getRow(0).isWrapped)
    }
}
