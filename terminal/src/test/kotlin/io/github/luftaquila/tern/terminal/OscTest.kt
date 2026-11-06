// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OscTest {
    @Test
    fun titleAndIconName() {
        val t = TestTerm()
        t.feed("${OSC}0;both\u0007")
        assertEquals("both", t.emu.title)
        t.feed("${OSC}1;icon only\u0007")
        assertEquals("both", t.emu.title)
        t.feed("${OSC}2;window$ST")
        assertEquals("window", t.emu.title)
        assertEquals(listOf("both", "window"), t.titles)
        t.feed("${OSC}2;\u0007")
        assertEquals("", t.emu.title)
    }

    @Test
    fun titleStack() {
        val t = TestTerm().feed("${OSC}2;first\u0007${CSI}22;0t${OSC}2;second\u0007")
        assertEquals("second", t.emu.title)
        t.feed("${CSI}23;0t")
        assertEquals("first", t.emu.title)
        t.feed("${CSI}23;0t") // empty stack: no change
        assertEquals("first", t.emu.title)
    }

    @Test
    fun workingDirectory() {
        val t = TestTerm()
        t.feed("${OSC}7;file://myhost/home/user/%ED%95%9C%EA%B8%80%20dir\u0007")
        assertEquals(listOf("/home/user/한글 dir"), t.cwds)
        t.feed("${OSC}7;file:///tmp/a+b$ST")
        assertEquals("/tmp/a+b", t.cwds.last())
        t.feed("${OSC}7;not-a-url\u0007")
        assertEquals(2, t.cwds.size)
    }

    @Test
    fun clipboardWrite() {
        val t = TestTerm()
        val payload = Base64.getEncoder().encodeToString("복사된 텍스트 ✓".toByteArray(Charsets.UTF_8))
        t.feed("${OSC}52;c;$payload\u0007")
        assertEquals(listOf("복사된 텍스트 ✓"), t.clipboard)
        t.feed("${OSC}52;c;?\u0007")
        assertEquals(1, t.clipboard.size)
        assertEquals("", t.replies())
        t.feed("${OSC}52;c;!!notbase64!!\u0007")
        assertEquals(1, t.clipboard.size)
    }

    @Test
    fun paletteSetAndReset() {
        val t = TestTerm()
        t.feed("${OSC}4;1;rgb:12/34/56;2;#abcdef\u0007X")
        val tmp = IntArray(2)
        fun fgOf(index: Int): Int {
            t.feed("${CSI}${30 + index}mX")
            t.emu.resolveColors(t.style(0, t.emu.cursorCol - 1), tmp)
            return tmp[0]
        }
        assertEquals(0xFF123456.toInt(), fgOf(1))
        assertEquals(0xFFABCDEF.toInt(), fgOf(2))
        t.feed("${OSC}104;1\u0007")
        assertEquals(ColorSchemes.DEFAULT.ansi[1], fgOf(1))
        assertEquals(0xFFABCDEF.toInt(), fgOf(2))
        t.feed("${OSC}104\u0007")
        assertEquals(ColorSchemes.DEFAULT.ansi[2], fgOf(2))
        assertTrue(t.colorChanges >= 3)
    }

    @Test
    fun dynamicColorsSetAndReset() {
        val t = TestTerm()
        t.feed("${OSC}10;#ff0000;#00ff00;#0000ff\u0007X")
        val c = t.colors(0, 0)
        assertEquals(0xFFFF0000.toInt(), c[0])
        assertEquals(0xFF00FF00.toInt(), c[1])
        assertEquals(0xFF0000FF.toInt(), t.emu.cursorColor)
        t.feed("${OSC}110\u0007${OSC}111\u0007${OSC}112\u0007")
        val d = t.colors(0, 0)
        assertEquals(ColorSchemes.DEFAULT.foreground, d[0])
        assertEquals(ColorSchemes.DEFAULT.background, d[1])
        assertEquals(ColorSchemes.DEFAULT.cursor, t.emu.cursorColor)
    }

    @Test
    fun colorSpecParsing() {
        assertEquals(0xFFFFFFFF.toInt(), TerminalEmulator.parseColor("rgb:f/f/f"))
        assertEquals(0xFF808080.toInt(), TerminalEmulator.parseColor("rgb:8080/8080/8080"))
        assertEquals(0xFF112233.toInt(), TerminalEmulator.parseColor("#112233"))
        assertEquals(0xFFF00000.toInt(), TerminalEmulator.parseColor("#f00"))
        // 16-bit components 0x1234/0x5678/0x9abc keep their high bytes.
        assertEquals(0xFF12569A.toInt(), TerminalEmulator.parseColor("#123456789abc"))
        assertEquals(null, TerminalEmulator.parseColor("red"))
        assertEquals(null, TerminalEmulator.parseColor("rgb:1/2"))
        assertEquals(null, TerminalEmulator.parseColor("#12345"))
    }

    @Test
    fun colorSchemeSetterResetsOverrides() {
        val t = TestTerm()
        t.feed("${OSC}11;#010203\u0007X")
        t.emu.colorScheme = ColorSchemes.NORD
        assertEquals(ColorSchemes.NORD.background, t.colors(0, 0)[1])
        assertEquals(ColorSchemes.NORD, t.emu.colorScheme)
    }
}
