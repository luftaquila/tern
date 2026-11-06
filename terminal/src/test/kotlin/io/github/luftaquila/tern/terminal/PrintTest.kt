// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintTest {
    @Test
    fun pendingWrapKeepsCursorOnLastColumn() {
        val t = TestTerm(cols = 5).feed("abcde")
        assertEquals(0 to 4, t.cursor)
        assertEquals("abcde", t.line(0))
        assertEquals("", t.line(1))
        t.feed("f")
        assertEquals("f", t.line(1))
        assertEquals(1 to 1, t.cursor)
        assertTrue(t.emu.getRow(0).isWrapped)
        assertFalse(t.emu.getRow(1).isWrapped)
    }

    @Test
    fun crClearsPendingWrap() {
        val t = TestTerm(cols = 5).feed("abcde\rX")
        assertEquals("Xbcde", t.line(0))
        assertEquals(0 to 1, t.cursor)
        assertFalse(t.emu.getRow(0).isWrapped)
    }

    @Test
    fun bashStyleSpaceCrWrap() {
        // readline writes the last column, then " \r" to force the wrap deterministically.
        val t = TestTerm(cols = 5).feed("abcde \rfg")
        assertEquals(listOf("abcde", "fg", "", "", ""), t.screen())
        assertEquals(1 to 2, t.cursor)
    }

    @Test
    fun backspaceFromPendingWrapMovesLeft() {
        val t = TestTerm(cols = 5).feed("abcde\bX")
        assertEquals("abcXe", t.line(0))
    }

    @Test
    fun noAutoWrapOverwritesLastColumn() {
        val t = TestTerm(cols = 5).feed("${CSI}?7labcdefg")
        assertEquals("abcdg", t.line(0))
        assertEquals(0 to 4, t.cursor)
        assertEquals("", t.line(1))
    }

    @Test
    fun eraseLineAtPendingWrapErasesLastChar() {
        val t = TestTerm(cols = 5).feed("abcde${CSI}K")
        assertEquals("abcd", t.line(0))
    }

    @Test
    fun hangulIsWide() {
        val t = TestTerm(cols = 10).feed("한글a")
        assertEquals("한글a", t.line(0))
        val r = t.emu.getRow(0)
        assertEquals('한'.code, r.getCodePoint(0))
        assertEquals(TerminalRow.WIDE_SPACER, r.getCodePoint(1))
        assertEquals('글'.code, r.getCodePoint(2))
        assertEquals(TerminalRow.WIDE_SPACER, r.getCodePoint(3))
        assertEquals('a'.code, r.getCodePoint(4))
        assertTrue(r.isWide(0))
        assertFalse(r.isWide(1))
        assertEquals(0 to 5, t.cursor)
    }

    @Test
    fun wideCharAtRightMarginWrapsFirst() {
        val t = TestTerm(cols = 5).feed("abcd한")
        assertEquals("abcd", t.line(0))
        assertEquals("한", t.line(1))
        assertTrue(t.emu.getRow(0).isWrapped)
        assertEquals(1 to 2, t.cursor)
    }

    @Test
    fun wideCharFillingLastTwoColumnsSetsPendingWrap() {
        val t = TestTerm(cols = 5).feed("abc한")
        assertEquals("abc한", t.line(0))
        assertEquals(0 to 4, t.cursor)
        t.feed("x")
        assertEquals("x", t.line(1))
    }

    @Test
    fun wideCharAtRightMarginWithoutAutowrap() {
        val t = TestTerm(cols = 5).feed("${CSI}?7labcd한")
        assertEquals("abc한", t.line(0))
    }

    @Test
    fun overwritingRightHalfBlanksLeftHalf() {
        val t = TestTerm(cols = 10).feed("한글${CSI}2Gx")
        assertEquals(" x글", t.line(0))
        assertEquals(0, t.emu.getRow(0).getCodePoint(0))
    }

    @Test
    fun overwritingLeftHalfBlanksRightHalf() {
        val t = TestTerm(cols = 10).feed("한글${CSI}1Gx")
        assertEquals("x 글", t.line(0))
        assertEquals(0, t.emu.getRow(0).getCodePoint(1))
    }

    @Test
    fun wideOverTwoNarrowAndHalfWide() {
        val t = TestTerm(cols = 10).feed("a한b${CSI}2G글")
        // '글' at col 1-2 overwrites the left half of 한 (cols 1-2) exactly.
        assertEquals("a글b", t.line(0))
        t.feed("${CSI}1G가")
        // '가' at col 0-1 hits the left half of '글' -> its right half (col 2) is blanked.
        assertEquals("가 b", t.line(0))
    }

    @Test
    fun combiningMarkAttachesToPreviousCell() {
        val t = TestTerm().feed("éx")
        val r = t.emu.getRow(0)
        assertEquals('e'.code, r.getCodePoint(0))
        assertEquals("́", r.getCombining(0))
        assertEquals('x'.code, r.getCodePoint(1))
        assertNull(r.getCombining(1))
        assertEquals(0 to 2, t.cursor)
    }

    @Test
    fun nfdHangulOccupiesOneWideCell() {
        // 한 = U+1112 U+1161 U+11AB in NFD (as macOS file names)
        val t = TestTerm().feed("한글!")
        val r = t.emu.getRow(0)
        assertEquals(0x1112, r.getCodePoint(0))
        assertEquals("ᅡᆫ", r.getCombining(0))
        assertEquals(TerminalRow.WIDE_SPACER, r.getCodePoint(1))
        assertEquals(0x1100, r.getCodePoint(2))
        assertEquals("ᅳᆯ", r.getCombining(2))
        assertEquals('!'.code, r.getCodePoint(4))
        assertEquals(0 to 5, t.cursor)
    }

    @Test
    fun combiningAtPendingWrapAttachesToLastCell() {
        val t = TestTerm(cols = 3).feed("abć")
        assertEquals("́", t.emu.getRow(0).getCombining(2))
        assertEquals(0 to 2, t.cursor)
    }

    @Test
    fun combiningAfterWideAtPendingWrapAttachesToWideChar() {
        val t = TestTerm(cols = 4).feed("ab하")
        val r = t.emu.getRow(0)
        assertEquals(0x1112, r.getCodePoint(2))
        assertEquals("ᅡ", r.getCombining(2))
    }

    @Test
    fun combiningAtColumnZeroAfterSoftWrapAttachesToPreviousLine() {
        val t = TestTerm(cols = 3).feed("abc")
        // force wrap then go back to col 0 of the continuation line without printing
        t.feed("d\b")
        t.feed("́")
        // cursor at (1,0): previous cell is (1,-1) -> previous row last cell since row 0 wrapped
        assertEquals("́", t.emu.getRow(0).getCombining(2))
    }

    @Test
    fun emojiIsWide() {
        val t = TestTerm().feed("🙂a")
        assertEquals(0 to 3, t.cursor)
        assertEquals(TerminalRow.WIDE_SPACER, t.emu.getRow(0).getCodePoint(1))
    }

    @Test
    fun zwjSequenceAttachesJoiner() {
        val t = TestTerm().feed("👩‍💻")
        val r = t.emu.getRow(0)
        assertEquals(0x1F469, r.getCodePoint(0))
        assertEquals("‍", r.getCombining(0))
        assertEquals(0x1F4BB, r.getCodePoint(2))
        assertEquals(0 to 4, t.cursor)
    }

    @Test
    fun decSpecialGraphics() {
        val t = TestTerm().feed("${ESC}(0lqqk${ESC}(Bx")
        assertEquals("┌──┐x", t.line(0))
    }

    @Test
    fun shiftOutUsesG1() {
        val t = TestTerm().feed("${ESC})0a\u000eqx\u000fq")
        assertEquals("a─│q", t.line(0))
    }

    @Test
    fun ukCharset() {
        val t = TestTerm().feed("${ESC}(A#${ESC}(B#")
        assertEquals("£#", t.line(0))
    }

    @Test
    fun singleShift() {
        val t = TestTerm().feed("${ESC}*0${ESC}Nqq")
        assertEquals("─q", t.line(0))
    }

    @Test
    fun charsetsDoNotAffectNonAscii() {
        val t = TestTerm().feed("${ESC}(0한q")
        assertEquals("한─", t.line(0))
    }

    @Test
    fun insertMode() {
        val t = TestTerm(cols = 6).feed("abcd${CSI}2G${CSI}4hXY${CSI}4lZ")
        // X and Y are inserted (a X Y b c d), then Z overwrites 'b' in replace mode.
        assertEquals("aXYZcd", t.line(0))
    }

    @Test
    fun insertModePushesCharsOffTheEdge() {
        val t = TestTerm(cols = 5).feed("abcde${CSI}1G${CSI}4hXY")
        assertEquals("XYabc", t.line(0))
    }

    @Test
    fun repeatPrecedingCharacter() {
        val t = TestTerm().feed("a${CSI}3b")
        assertEquals("aaaa", t.line(0))
        t.feed("\r\n${ESC}(0q${CSI}2b${ESC}(B")
        assertEquals("───", t.line(1))
    }

    @Test
    fun repeatWideChar() {
        val t = TestTerm().feed("가${CSI}2b")
        assertEquals("가가가", t.line(0))
        assertEquals(0 to 6, t.cursor)
    }

    @Test
    fun printUsesCurrentStyle() {
        val t = TestTerm().feed("${CSI}1;31ma${CSI}0mb")
        val f = TextStyle.flags(t.style(0, 0))
        assertTrue(f and TextStyle.BOLD != 0)
        assertEquals(TextStyle.DEFAULT, t.style(0, 1))
    }
}
