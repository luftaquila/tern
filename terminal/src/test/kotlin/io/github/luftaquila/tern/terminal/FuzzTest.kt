// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class FuzzTest {
    private fun checkInvariants(e: TerminalEmulator) {
        assertTrue(e.cursorRow in 0 until e.rows, "cursorRow ${e.cursorRow} rows ${e.rows}")
        assertTrue(e.cursorCol in 0 until e.columns, "cursorCol ${e.cursorCol} cols ${e.columns}")
        assertTrue(e.historySize >= 0)
        for (r in -e.historySize until e.rows) {
            val row = e.getRow(r)
            assertTrue(row.columns == e.columns, "row $r has ${row.columns} columns, expected ${e.columns}")
            // A spacer is always preceded by its wide char.
            for (c in 0 until row.columns) {
                if (row.getCodePoint(c) == TerminalRow.WIDE_SPACER) {
                    assertTrue(c > 0 && row.getCodePoint(c - 1) != TerminalRow.WIDE_SPACER && row.getCodePoint(c - 1) != 0, "orphan spacer at $r,$c")
                }
            }
        }
        // Text extraction must not throw.
        e.getText(-e.historySize, 0, e.rows - 1, e.columns)
    }

    @Test
    fun randomBytes() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val e = TerminalEmulator(rnd.nextInt(2, 120), rnd.nextInt(1, 60), 200, ColorSchemes.DEFAULT) {}
            repeat(200) {
                val chunk = ByteArray(rnd.nextInt(1, 512)) { rnd.nextInt(256).toByte() }
                e.feed(chunk)
                if (rnd.nextInt(20) == 0) e.resize(rnd.nextInt(1, 150), rnd.nextInt(1, 70))
            }
            checkInvariants(e)
        }
    }

    private val fragments = listOf(
        "\u001b[", "\u001b]", "\u001bP", "\u001b(", "\u001b)0", "\u001b#8", "\u001b7", "\u001b8", "\u001bD",
        "\u001bM", "\u001bE", "\u001bc", "\u0007", "\u001b\\", "\r", "\n", "\t", "\b", "\u000e", "\u000f",
        "?", ">", ";", ":", "1", "2", "0", "9", "25", "1049", "47", "1047", "2004", "6", "7", "200",
        "h", "l", "m", "H", "J", "K", "L", "M", "P", "@", "X", "S", "T", "r", "b", "c", "n", "t", " q", "\$p", "!p",
        "A", "B", "C", "D", "G", "d", "s", "u", "I", "Z", "g", "38;5;", "48;2;", "4:3",
        "한", "글", "가나다", "🙂", "́", "ᅡ", "ᆫ", "ᄒ", "e", "x", "abc", " ", "漢",
        "‍", "️", "q", "0;title", "52;c;aGk=", "4;1;rgb:1/2/3", "10;?", "\u0018", "\u001a",
    )

    @Test
    fun randomEscapeSequences() {
        for (seed in 100..160) {
            val rnd = Random(seed)
            val e = TerminalEmulator(rnd.nextInt(2, 100), rnd.nextInt(1, 50), 100, ColorSchemes.DEFAULT) {}
            val sb = StringBuilder()
            repeat(3000) {
                sb.append(fragments[rnd.nextInt(fragments.size)])
                if (sb.length > 200) {
                    val bytes = sb.toString().toByteArray(Charsets.UTF_8)
                    // Split at random points, including inside UTF-8 sequences.
                    var i = 0
                    while (i < bytes.size) {
                        val n = minOf(bytes.size - i, rnd.nextInt(1, 64))
                        e.feed(bytes, i, n)
                        i += n
                    }
                    sb.setLength(0)
                    if (rnd.nextInt(10) == 0) e.resize(rnd.nextInt(1, 120), rnd.nextInt(1, 60))
                }
            }
            checkInvariants(e)
        }
    }
}
