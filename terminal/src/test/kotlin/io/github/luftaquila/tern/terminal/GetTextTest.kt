// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class GetTextTest {
    @Test
    fun singleLineRange() {
        val t = TestTerm(cols = 20).feed("hello world")
        assertEquals("hello", t.emu.getText(0, 0, 0, 5))
        assertEquals("world", t.emu.getText(0, 6, 0, 11))
        assertEquals("hello world", t.emu.getText(0, 0, 0, 20))
    }

    @Test
    fun hardLineBreaksAndTrimming() {
        val t = TestTerm(cols = 10).feed("ab   \r\ncd\r\n\r\nef")
        assertEquals("ab\ncd\n\nef", t.emu.getText(0, 0, 3, 10))
    }

    @Test
    fun softWrapJoinsWithoutNewlineOrTrim() {
        val t = TestTerm(cols = 5).feed("abc  defg")
        assertEquals("abc  defg", t.emu.getText(0, 0, 1, 5))
    }

    @Test
    fun wideCharsAndCombining() {
        val t = TestTerm(cols = 10).feed("한글 é")
        assertEquals("한글 é", t.emu.getText(0, 0, 0, 10))
        // Starting on a spacer includes the wide char.
        assertEquals("한글", t.emu.getText(0, 1, 0, 4))
        assertEquals("한", t.emu.getText(0, 0, 0, 2))
    }

    @Test
    fun reversedRangeAndHistory() {
        val t = TestTerm(cols = 10, rows = 2)
        t.feed("one\r\ntwo\r\nthree")
        assertEquals("one\ntwo\nthree", t.emu.getText(-1, 0, 1, 10))
        // Reversed ranges are normalized.
        assertEquals("one\ntwo", t.emu.getText(0, 10, -1, 0))
        assertEquals("two\nthr", t.emu.getText(1, 3, 0, 0))
    }

    @Test
    fun outOfRangeIsClamped() {
        val t = TestTerm(cols = 10, rows = 2).feed("ab")
        // Clamped to (0,0)..(1,10): the hard line end of row 0 is kept, empty row 1 adds nothing.
        assertEquals("ab\n", t.emu.getText(-50, 0, 50, 0))
    }
}
