// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AltScreenTest {
    @Test
    fun mode1049SavesCursorClearsAndRestores() {
        val t = TestTerm(cols = 10, rows = 3, scrollback = 10)
        t.feed("one\r\ntwo\r\nthree\r\nfour")
        assertEquals(1, t.emu.historySize)
        val before = t.screen()
        val cursor = t.cursor
        t.feed("${CSI}?1049h")
        assertTrue(t.emu.isAlternateScreen)
        assertEquals(listOf("", "", ""), t.screen())
        assertEquals(0, t.emu.historySize)
        t.feed("${CSI}Hvim${CSI}3;1H~")
        assertEquals(listOf("vim", "", "~"), t.screen())
        t.feed("${CSI}?1049l")
        assertFalse(t.emu.isAlternateScreen)
        assertEquals(before, t.screen())
        assertEquals(cursor, t.cursor)
        assertEquals(1, t.emu.historySize)
    }

    @Test
    fun alternateScreenDoesNotFeedHistory() {
        val t = TestTerm(cols = 10, rows = 3)
        t.feed("${CSI}?1049h")
        for (i in 1..10) t.feed("x$i\r\n")
        assertEquals(0, t.emu.historySize)
        assertEquals(0L, t.emu.scrolledLines)
        t.feed("${CSI}?1049l")
        assertEquals(0, t.emu.historySize)
    }

    @Test
    fun mode1049ReEnterClearsAlternate() {
        val t = TestTerm(cols = 10, rows = 3)
        t.feed("${CSI}?1049hjunk${CSI}?1049l${CSI}?1049h")
        assertEquals(listOf("", "", ""), t.screen())
    }

    @Test
    fun mode47KeepsAlternateContent() {
        val t = TestTerm(cols = 10, rows = 3)
        t.feed("main${CSI}?47halt${CSI}?47l")
        assertEquals("main", t.line(0))
        t.feed("${CSI}?47h")
        // Mode 47 neither clears nor moves the cursor: "alt" was written at column 4.
        assertEquals("    alt", t.line(0))
    }

    @Test
    fun mode1047ClearsAlternateOnExit() {
        val t = TestTerm(cols = 10, rows = 3)
        t.feed("main${CSI}?1047h${CSI}Halt${CSI}?1047l")
        assertEquals("main", t.line(0))
        t.feed("${CSI}?1047h")
        assertEquals("", t.line(0))
    }

    @Test
    fun savedCursorIsPerScreen() {
        val t = TestTerm(cols = 10, rows = 5)
        t.feed("${CSI}2;2H${ESC}7${CSI}?1049h${CSI}4;4H${ESC}7${CSI}H${ESC}8")
        assertEquals(3 to 3, t.cursor)
        t.feed("${CSI}?1049l")
        assertEquals(1 to 1, t.cursor)
        t.feed("${CSI}H${ESC}8")
        assertEquals(1 to 1, t.cursor)
    }
}
