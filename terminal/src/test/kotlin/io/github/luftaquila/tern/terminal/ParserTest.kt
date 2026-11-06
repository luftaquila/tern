// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserTest {
    @Test
    fun plainText() {
        val t = TestTerm().feed("hello")
        assertEquals("hello", t.line(0))
        assertEquals(0 to 5, t.cursor)
    }

    @Test
    fun utf8SplitAcrossFeeds() {
        val t = TestTerm()
        val bytes = "가나".toByteArray(Charsets.UTF_8)
        for (b in bytes) t.feedBytes(byteArrayOf(b))
        assertEquals("가나", t.line(0))
        assertEquals(0 to 4, t.cursor)
    }

    @Test
    fun fourByteUtf8SplitAcrossFeeds() {
        val t = TestTerm()
        val bytes = "🙂x".toByteArray(Charsets.UTF_8)
        t.feedBytes(bytes.copyOfRange(0, 2))
        t.feedBytes(bytes.copyOfRange(2, bytes.size))
        assertEquals("🙂x", t.line(0))
        assertEquals(0 to 3, t.cursor)
    }

    @Test
    fun invalidUtf8BecomesReplacement() {
        val t = TestTerm()
        t.feedBytes(byteArrayOf(0xC3.toByte(), 'a'.code.toByte())) // truncated sequence
        t.feedBytes(byteArrayOf(0xFF.toByte())) // invalid lead byte
        t.feedBytes(byteArrayOf(0xC0.toByte(), 0x80.toByte())) // overlong NUL
        t.feedBytes(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())) // surrogate
        assertEquals("�a���", t.line(0))
    }

    @Test
    fun escapeSequenceSplitAcrossFeeds() {
        val t = TestTerm()
        val seq = "${CSI}31;1mX${CSI}0m"
        for (ch in seq) t.feed(ch.toString())
        assertEquals("X", t.line(0))
        val flags = TextStyle.flags(t.style(0, 0))
        assertTrue(flags and TextStyle.BOLD != 0)
    }

    @Test
    fun cancelAbortsSequence() {
        val t = TestTerm().feed("${CSI}31\u0018m")
        assertEquals("m", t.line(0))
        assertEquals(TextStyle.DEFAULT, t.style(0, 0))
    }

    @Test
    fun unknownSequencesAreConsumed() {
        val t = TestTerm().feed("a${CSI}?999;1zb${ESC}#5c${CSI}>4;1md${CSI}=5ue${CSI}1\$wf")
        assertEquals("abcdef", t.line(0))
    }

    @Test
    fun c0ControlsExecuteInsideCsi() {
        // VT500: C0 controls inside a sequence are executed immediately.
        val t = TestTerm().feed("ab$CSI\r2Cx")
        // CR runs inside the CSI (col 0), then CUF 2 -> col 2 where 'x' overwrites nothing.
        assertEquals("abx", t.line(0))
        assertEquals(0 to 3, t.cursor)
    }

    @Test
    fun oscTerminatedByBelAndSt() {
        val t = TestTerm().feed("${OSC}0;one\u0007${OSC}2;two$ST")
        assertEquals(listOf("one", "two"), t.titles)
        assertEquals("", t.line(0))
    }

    @Test
    fun oscWithUtf8Title() {
        val t = TestTerm().feed("${OSC}2;한글 제목\u0007")
        assertEquals("한글 제목", t.emu.title)
    }

    @Test
    fun oscAbortedByCanDoesNotDispatch() {
        val t = TestTerm().feed("${OSC}2;nope\u0018ok")
        assertTrue(t.titles.isEmpty())
        assertEquals("ok", t.line(0))
    }

    @Test
    fun oscTerminatedByEscStartingNewSequence() {
        val t = TestTerm().feed("${OSC}2;t1${CSI}1mX")
        assertEquals(listOf("t1"), t.titles)
        assertEquals("X", t.line(0))
        assertTrue(TextStyle.flags(t.style(0, 0)) and TextStyle.BOLD != 0)
    }

    @Test
    fun dcsAndApcStringsAreIgnored() {
        val t = TestTerm().feed("a${ESC}Pq#0;2;0;0;0#0~~@@vv$ST" + "b${ESC}_Gf=24,s=1;AAAA${ST}c${ESC}^pm$ST${ESC}Xsos${ST}d")
        assertEquals("abcd", t.line(0))
    }

    @Test
    fun privateMarkerDoesNotTriggerCursorRestore() {
        val t = TestTerm().feed("${ESC}7")
        t.feed("${CSI}3;5H")
        t.feed("${CSI}>1u") // kitty keyboard push - must not restore cursor
        t.feed("${CSI}?u") // kitty keyboard query
        assertEquals(2 to 4, t.cursor)
        t.feed("${CSI}u")
        assertEquals(0 to 0, t.cursor)
    }

    @Test
    fun tooManyParamsAreTolerated() {
        val sb = StringBuilder(CSI)
        repeat(100) { sb.append("1;") }
        sb.append("31mX")
        val t = TestTerm().feed(sb.toString())
        assertEquals("X", t.line(0))
    }

    @Test
    fun hugeParamIsClamped() {
        val t = TestTerm().feed("${CSI}99999999999999999999Cx")
        assertEquals(0 to 19, t.cursor)
        assertEquals("x", t.line(0).trim())
    }

    @Test
    fun delAndC1AreIgnoredInGround() {
        val t = TestTerm().feed("a\u007fb\u0085c\u009bd")
        assertEquals("abcd", t.line(0))
    }

    @Test
    fun bel() {
        val t = TestTerm().feed("\u0007x\u0007")
        assertEquals(2, t.bells)
    }

    @Test
    fun nonAsciiAbortsEscapeAndPrints() {
        val t = TestTerm().feed("${ESC}한")
        assertEquals("한", t.line(0))
    }
}
