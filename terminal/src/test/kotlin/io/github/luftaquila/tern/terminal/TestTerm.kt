// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import java.io.ByteArrayOutputStream

/** Small harness around [TerminalEmulator] for tests. */
class TestTerm(
    cols: Int = 20,
    rows: Int = 5,
    scrollback: Int = 100,
    scheme: ColorScheme = ColorSchemes.DEFAULT,
) {
    private val out = ByteArrayOutputStream()
    val titles = mutableListOf<String>()
    val clipboard = mutableListOf<String>()
    val cwds = mutableListOf<String>()
    var bells = 0
    var colorChanges = 0

    val emu = TerminalEmulator(cols, rows, scrollback, scheme) { out.write(it) }.also {
        it.listener = object : TerminalListener {
            override fun onTitleChanged(title: String) {
                titles += title
            }

            override fun onBell() {
                bells++
            }

            override fun onClipboardWrite(text: String) {
                clipboard += text
            }

            override fun onColorsChanged() {
                colorChanges++
            }

            override fun onWorkingDirectoryChanged(path: String) {
                cwds += path
            }
        }
    }

    fun feed(s: String): TestTerm {
        emu.feed(s.toByteArray(Charsets.UTF_8))
        return this
    }

    fun feedBytes(b: ByteArray): TestTerm {
        emu.feed(b)
        return this
    }

    /** Text of a row: empty cells as spaces, spacers skipped, combining kept, trailing blanks trimmed. */
    fun line(row: Int): String {
        val r = emu.getRow(row)
        val sb = StringBuilder()
        for (c in 0 until r.columns) {
            val cp = r.getCodePoint(c)
            if (cp == TerminalRow.WIDE_SPACER) continue
            if (cp == 0) sb.append(' ') else if (ImageCell.isImage(cp)) sb.append('#') else sb.appendCodePoint(cp)
            r.getCombining(c)?.let { sb.append(it) }
        }
        return sb.toString().trimEnd()
    }

    fun screen(): List<String> = (0 until emu.rows).map { line(it) }

    fun history(): List<String> = (-emu.historySize until 0).map { line(it) }

    /** Bytes the emulator sent back to the host since the last call. */
    fun replies(): String {
        val s = out.toString(Charsets.UTF_8)
        out.reset()
        return s
    }

    val cursor: Pair<Int, Int> get() = emu.cursorRow to emu.cursorCol

    fun style(row: Int, col: Int): Long = emu.getRow(row).getStyle(col)

    fun colors(row: Int, col: Int): IntArray = IntArray(2).also { emu.resolveColors(style(row, col), it) }
}

const val ESC = "\u001b"
const val CSI = "\u001b["
const val OSC = "\u001b]"
const val ST = "\u001b\\"
