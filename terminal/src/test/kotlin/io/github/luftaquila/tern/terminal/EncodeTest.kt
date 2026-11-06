// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EncodeTest {
    private fun s(b: ByteArray?) = b?.toString(Charsets.UTF_8)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val mousePrefix = intArrayOf(27, '['.code, 'M'.code)

    private fun mouse(cb: Int, x: Int, y: Int) = bytes(*mousePrefix, cb, x, y)

    @Test
    fun cursorKeysNormalAndApplication() {
        val t = TestTerm()
        assertEquals("${CSI}A", s(t.emu.encodeKey(TerminalKey.UP)))
        assertEquals("${CSI}D", s(t.emu.encodeKey(TerminalKey.LEFT)))
        assertEquals("${CSI}H", s(t.emu.encodeKey(TerminalKey.HOME)))
        assertEquals("${CSI}F", s(t.emu.encodeKey(TerminalKey.END)))
        t.feed("${CSI}?1h")
        assertEquals("${ESC}OA", s(t.emu.encodeKey(TerminalKey.UP)))
        assertEquals("${ESC}OC", s(t.emu.encodeKey(TerminalKey.RIGHT)))
        assertEquals("${ESC}OH", s(t.emu.encodeKey(TerminalKey.HOME)))
        assertEquals("${CSI}1;5A", s(t.emu.encodeKey(TerminalKey.UP, KeyMod.CTRL)))
        assertEquals("${CSI}1;2B", s(t.emu.encodeKey(TerminalKey.DOWN, KeyMod.SHIFT)))
        assertEquals("${CSI}1;3D", s(t.emu.encodeKey(TerminalKey.LEFT, KeyMod.ALT)))
        assertEquals("${CSI}1;8C", s(t.emu.encodeKey(TerminalKey.RIGHT, KeyMod.SHIFT or KeyMod.ALT or KeyMod.CTRL)))
    }

    @Test
    fun editingAndFunctionKeys() {
        val e = TestTerm().emu
        assertEquals("${CSI}2~", s(e.encodeKey(TerminalKey.INSERT)))
        assertEquals("${CSI}3~", s(e.encodeKey(TerminalKey.DELETE)))
        assertEquals("${CSI}5~", s(e.encodeKey(TerminalKey.PAGE_UP)))
        assertEquals("${CSI}6;5~", s(e.encodeKey(TerminalKey.PAGE_DOWN, KeyMod.CTRL)))
        assertEquals("${ESC}OP", s(e.encodeKey(TerminalKey.F1)))
        assertEquals("${ESC}OS", s(e.encodeKey(TerminalKey.F4)))
        assertEquals("${CSI}1;2Q", s(e.encodeKey(TerminalKey.F2, KeyMod.SHIFT)))
        val expected = mapOf(
            TerminalKey.F5 to 15, TerminalKey.F6 to 17, TerminalKey.F7 to 18, TerminalKey.F8 to 19,
            TerminalKey.F9 to 20, TerminalKey.F10 to 21, TerminalKey.F11 to 23, TerminalKey.F12 to 24,
        )
        for ((k, n) in expected) {
            assertEquals("$CSI$n~", s(e.encodeKey(k)))
            assertEquals("$CSI$n;5~", s(e.encodeKey(k, KeyMod.CTRL)))
        }
    }

    @Test
    fun controlKeys() {
        val t = TestTerm()
        val e = t.emu
        assertEquals("\u007f", s(e.encodeKey(TerminalKey.BACKSPACE)))
        assertEquals("\b", s(e.encodeKey(TerminalKey.BACKSPACE, KeyMod.CTRL)))
        assertEquals("$ESC\u007f", s(e.encodeKey(TerminalKey.BACKSPACE, KeyMod.ALT)))
        assertEquals("\t", s(e.encodeKey(TerminalKey.TAB)))
        assertEquals("${CSI}Z", s(e.encodeKey(TerminalKey.TAB, KeyMod.SHIFT)))
        assertEquals("\r", s(e.encodeKey(TerminalKey.ENTER)))
        assertEquals("$ESC\r", s(e.encodeKey(TerminalKey.ENTER, KeyMod.ALT)))
        assertEquals(ESC, s(e.encodeKey(TerminalKey.ESCAPE)))
        assertEquals("$ESC$ESC", s(e.encodeKey(TerminalKey.ESCAPE, KeyMod.ALT)))
    }

    @Test
    fun codePointsWithModifiers() {
        val e = TestTerm().emu
        assertContentEquals(byteArrayOf(3), e.encodeCodePoint('c'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(3), e.encodeCodePoint('C'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(0), e.encodeCodePoint(' '.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(0), e.encodeCodePoint('@'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(0), e.encodeCodePoint('2'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(27), e.encodeCodePoint('['.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(27), e.encodeCodePoint('3'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(28), e.encodeCodePoint('\\'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(29), e.encodeCodePoint(']'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(30), e.encodeCodePoint('^'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(31), e.encodeCodePoint('_'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(31), e.encodeCodePoint('/'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(127), e.encodeCodePoint('?'.code, KeyMod.CTRL))
        assertContentEquals(byteArrayOf(127), e.encodeCodePoint('8'.code, KeyMod.CTRL))
        assertEquals("${ESC}x", s(e.encodeCodePoint('x'.code, KeyMod.ALT)))
        assertContentEquals(byteArrayOf(27, 24), e.encodeCodePoint('x'.code, KeyMod.ALT or KeyMod.CTRL))
        assertEquals("한", s(e.encodeCodePoint('한'.code)))
        assertEquals("1", s(e.encodeCodePoint('1'.code, KeyMod.CTRL)))
        assertEquals("🙂", s(e.encodeCodePoint(0x1F642)))
    }

    @Test
    fun paste() {
        val t = TestTerm()
        assertEquals("a\rb\rc", s(t.emu.encodePaste("a\r\nb\nc")))
        t.feed("${CSI}?2004h")
        assertEquals("${CSI}200~a\rb${CSI}201~", s(t.emu.encodePaste("a\nb")))
        // An embedded end marker must not let pasted text escape the bracket.
        assertEquals("${CSI}200~xrm -rf${CSI}201~", s(t.emu.encodePaste("x${CSI}201~rm -rf")))
        assertEquals("${CSI}200~한글${CSI}201~", s(t.emu.encodePaste("한글")))
    }

    @Test
    fun mouseDisabledByDefault() {
        val e = TestTerm().emu
        assertNull(e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 0, 0))
    }

    @Test
    fun mouseNormalModeDefaultEncoding() {
        val t = TestTerm().feed("${CSI}?1000h")
        val e = t.emu
        assertContentEquals(mouse(32, 32 + 5, 32 + 3), e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 4, 2))
        assertContentEquals(mouse(32 + 3, 33, 33), e.encodeMouse(MouseAction.RELEASE, MouseButton.LEFT, 0, 0))
        assertContentEquals(mouse(32 + 64, 33, 33), e.encodeMouse(MouseAction.WHEEL_UP, MouseButton.NONE, 0, 0))
        assertContentEquals(mouse(32 + 2 + 16, 33, 33), e.encodeMouse(MouseAction.PRESS, MouseButton.RIGHT, 0, 0, KeyMod.CTRL))
        // Column 222 (1-based 223) is the last one the default encoding can carry.
        assertContentEquals(mouse(32, 255, 33), e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 222, 0))
        assertNull(e.encodeMouse(MouseAction.MOTION, MouseButton.LEFT, 1, 1))
        assertNull(e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 223, 0))
    }

    @Test
    fun mouseSgrEncodingAndMotionModes() {
        val t = TestTerm().feed("${CSI}?1002h${CSI}?1006h")
        val e = t.emu
        assertEquals("${CSI}<0;5;3M", s(e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 4, 2)))
        assertEquals("${CSI}<0;5;3m", s(e.encodeMouse(MouseAction.RELEASE, MouseButton.LEFT, 4, 2)))
        assertEquals("${CSI}<32;6;3M", s(e.encodeMouse(MouseAction.MOTION, MouseButton.LEFT, 5, 2)))
        assertNull(e.encodeMouse(MouseAction.MOTION, MouseButton.NONE, 5, 2))
        assertEquals("${CSI}<65;1;1M", s(e.encodeMouse(MouseAction.WHEEL_DOWN, MouseButton.NONE, 0, 0)))
        // SGR encoding has no coordinate limit.
        assertEquals("${CSI}<0;301;1M", s(e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 300, 0)))
        t.feed("${CSI}?1003h")
        assertEquals("${CSI}<35;1;1M", s(e.encodeMouse(MouseAction.MOTION, MouseButton.NONE, 0, 0)))
        assertEquals("${CSI}<12;1;1M", s(e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 0, 0, KeyMod.SHIFT or KeyMod.ALT)))
        t.feed("${CSI}?1003l")
        assertNull(e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 0, 0))
    }

    @Test
    fun mouseX10Mode() {
        val t = TestTerm().feed("${CSI}?9h")
        val e = t.emu
        assertContentEquals(mouse(32, 33, 33), e.encodeMouse(MouseAction.PRESS, MouseButton.LEFT, 0, 0, KeyMod.CTRL))
        assertNull(e.encodeMouse(MouseAction.RELEASE, MouseButton.LEFT, 0, 0))
        assertNull(e.encodeMouse(MouseAction.WHEEL_UP, MouseButton.NONE, 0, 0))
        assertEquals(MouseTrackingMode.X10, e.mouseTrackingMode)
    }

    @Test
    fun focusReporting() {
        val t = TestTerm()
        assertNull(t.emu.encodeFocus(true))
        t.feed("${CSI}?1004h")
        assertEquals("${CSI}I", s(t.emu.encodeFocus(true)))
        assertEquals("${CSI}O", s(t.emu.encodeFocus(false)))
    }

    @Test
    fun modeFlagsExposed() {
        val t = TestTerm().feed("${CSI}?1h${ESC}=${CSI}?2004h${CSI}?1006h")
        val e = t.emu
        assertEquals(true, e.isApplicationCursorKeys)
        assertEquals(true, e.isApplicationKeypad)
        assertEquals(true, e.isBracketedPaste)
        assertEquals(true, e.isMouseSgr)
        t.feed("${ESC}>${CSI}!p")
        assertEquals(false, e.isApplicationKeypad)
        assertEquals(false, e.isApplicationCursorKeys)
    }
}
