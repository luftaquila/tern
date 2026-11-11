// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.termview

import io.github.luftaquila.tern.terminal.ColorScheme
import io.github.luftaquila.tern.terminal.TerminalEmulator
import io.github.luftaquila.tern.terminal.TerminalOutput
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A terminal that only shows [content] (text with escape sequences), redrawn after every resize,
 * so a [TerminalView] can preview fonts exactly as sessions render them. Input is dropped.
 */
class PreviewBackend(scheme: ColorScheme, private val content: String) : TerminalBackend {
    override val lock = Any()
    override val emulator = TerminalEmulator(columns = 40, rows = 8, scrollbackLines = 0, colorScheme = scheme, output = TerminalOutput { })
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        render()
    }

    override fun write(data: ByteArray) = Unit

    override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        synchronized(lock) {
            emulator.resize(columns, rows)
            emulator.setPixelSize(widthPx, heightPx)
        }
        render()
    }

    override fun reportSize() = Unit

    override fun addScreenListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeScreenListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun render() {
        // RIS clears the screen and the cursor position before the content is drawn again.
        synchronized(lock) { emulator.feed("\u001bc$content".toByteArray(Charsets.UTF_8)) }
        listeners.forEach { it() }
    }
}
