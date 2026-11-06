// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SgrTest {
    private val scheme = ColorSchemes.DEFAULT

    private fun cell(seq: String): Pair<Int, IntArray> {
        val t = TestTerm().feed("${seq}X")
        return TextStyle.flags(t.style(0, 0)) to t.colors(0, 0)
    }

    @Test
    fun attributes() {
        val (f, _) = cell("${CSI}1;2;3;4;5;7;8;9m")
        val expected = TextStyle.BOLD or TextStyle.FAINT or TextStyle.ITALIC or TextStyle.UNDERLINE or
            TextStyle.BLINK or TextStyle.INVERSE or TextStyle.INVISIBLE or TextStyle.STRIKETHROUGH
        assertEquals(expected, f)
    }

    @Test
    fun attributeResets() {
        val (f, _) = cell("${CSI}1;2;3;4;5;7;8;9m${CSI}22;23;24;25;27;28;29m")
        assertEquals(0, f)
        val (f2, _) = cell("${CSI}1;4m${CSI}m")
        assertEquals(0, f2)
        val (f3, _) = cell("${CSI}1;4m${CSI}0m")
        assertEquals(0, f3)
    }

    @Test
    fun underlineStyles() {
        assertEquals(TextStyle.DOUBLE_UNDERLINE, cell("${CSI}21m").first)
        assertEquals(TextStyle.CURLY_UNDERLINE, cell("${CSI}4:3m").first)
        assertEquals(TextStyle.DOUBLE_UNDERLINE, cell("${CSI}4:2m").first)
        assertEquals(TextStyle.UNDERLINE, cell("${CSI}4:4m").first)
        assertEquals(0, cell("${CSI}4m${CSI}4:0m").first)
        assertEquals(TextStyle.UNDERLINE, cell("${CSI}4:3m${CSI}4m").first)
    }

    @Test
    fun basicAndBrightColors() {
        assertEquals(scheme.ansi[1], cell("${CSI}31m").second[0])
        assertEquals(scheme.ansi[4], cell("${CSI}44m").second[1])
        assertEquals(scheme.ansi[9], cell("${CSI}91m").second[0])
        assertEquals(scheme.ansi[12], cell("${CSI}104m").second[1])
        val (_, c) = cell("${CSI}31;44m${CSI}39;49m")
        assertEquals(scheme.foreground, c[0])
        assertEquals(scheme.background, c[1])
    }

    @Test
    fun indexedColors() {
        assertEquals(0xFF87AFFF.toInt(), cell("${CSI}38;5;111m").second[0]) // cube (2,3,5)
        assertEquals(0xFF808080.toInt(), cell("${CSI}48;5;244m").second[1]) // gray 8+10*12
        assertEquals(scheme.ansi[3], cell("${CSI}38;5;3m").second[0])
        assertEquals(0xFF87AFFF.toInt(), cell("${CSI}38:5:111m").second[0])
    }

    @Test
    fun trueColorForms() {
        assertEquals(0xFF0AC81E.toInt(), cell("${CSI}38;2;10;200;30m").second[0])
        assertEquals(0xFF0AC81E.toInt(), cell("${CSI}38:2::10:200:30m").second[0])
        assertEquals(0xFF0AC81E.toInt(), cell("${CSI}38:2:10:200:30m").second[0])
        assertEquals(0xFF0AC81E.toInt(), cell("${CSI}48:2:0:10:200:30m").second[1])
    }

    @Test
    fun extendedColorFollowedByMoreParams() {
        val (f, c) = cell("${CSI}38;2;1;2;3;1;48;5;196;4m")
        assertEquals(0xFF010203.toInt(), c[0])
        assertEquals(0xFFFF0000.toInt(), c[1])
        assertEquals(TextStyle.BOLD or TextStyle.UNDERLINE, f)
    }

    @Test
    fun underlineColorIsConsumedNotMisread() {
        // 58;2;255;0;0 must not be read as "faint, 255, reset, reset"
        val (f, c) = cell("${CSI}1;58;2;255;0;0;31m")
        assertEquals(TextStyle.BOLD, f)
        assertEquals(scheme.ansi[1], c[0])
        val (f2, _) = cell("${CSI}1;58:5:200m")
        assertEquals(TextStyle.BOLD, f2)
    }

    @Test
    fun inverseAndReverseVideo() {
        val (_, c) = cell("${CSI}7m")
        assertEquals(scheme.background, c[0])
        assertEquals(scheme.foreground, c[1])
        val t = TestTerm().feed("${CSI}?5hX")
        assertEquals(scheme.background, t.colors(0, 0)[0])
        assertEquals(scheme.foreground, t.colors(0, 0)[1])
        t.feed("${CSI}7mY")
        assertEquals(scheme.foreground, t.colors(0, 1)[0])
        assertEquals(true, t.emu.isReverseVideo)
    }

    @Test
    fun faintAndInvisible() {
        val (_, c) = cell("${CSI}2m")
        assertNotEquals(scheme.foreground, c[0])
        val (_, c2) = cell("${CSI}8m")
        assertEquals(c2[1], c2[0])
    }

    @Test
    fun styleSurvivesErase() {
        val t = TestTerm().feed("${CSI}41m${CSI}2J")
        assertEquals(scheme.ansi[1], t.colors(3, 10)[1])
        t.feed("${CSI}0m${CSI}2J")
        assertEquals(scheme.background, t.colors(3, 10)[1])
    }
}
