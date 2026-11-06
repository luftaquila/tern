// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays raw output recorded from real programs inside tmux and compares the resulting
 * screen with tmux's own rendering (see src/test/resources/sessions/README.md).
 */
class RealSessionTest {
    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/sessions/$name")!!.use { it.readBytes() }

    private fun replay(name: String, chunk: Int = Int.MAX_VALUE) {
        val meta = String(resource("$name.meta")).trim().split(' ').map { it.toInt() }
        val (cursorY, cursorX, width, height, alt) = meta
        val raw = resource("$name.raw")
        val expected = String(resource("$name.screen"), Charsets.UTF_8).split('\n')
            .map { it.trimEnd() }.let { lines -> (0 until height).map { lines.getOrElse(it) { "" } } }

        val t = TestTerm(cols = width, rows = height, scrollback = 1000)
        var i = 0
        while (i < raw.size) {
            val n = minOf(chunk, raw.size - i)
            t.emu.feed(raw, i, n)
            i += n
        }
        assertEquals(expected.joinToString("\n"), t.screen().joinToString("\n"), "screen of $name (chunk=$chunk)")
        assertEquals(cursorY to cursorX, t.cursor, "cursor of $name")
        assertEquals(alt == 1, t.emu.isAlternateScreen, "alternate screen of $name")
    }

    private fun replayAllChunkings(name: String) {
        replay(name)
        // Splitting the stream (including inside UTF-8 and escape sequences) must not matter.
        replay(name, chunk = 1)
        replay(name, chunk = 7)
    }

    @Test
    fun bashWithKoreanAndColors() = replayAllChunkings("shell")

    @Test
    fun vimScrolling() = replayAllChunkings("vim")

    @Test
    fun vimNarrowWithWrappedKorean() = replayAllChunkings("vimnarrow")

    @Test
    fun vimSplitWindows() = replayAllChunkings("vimsplit")

    @Test
    fun neovim() = replayAllChunkings("nvim")

    @Test
    fun less() = replayAllChunkings("less")

    @Test
    fun htop() = replayAllChunkings("htop")
}
