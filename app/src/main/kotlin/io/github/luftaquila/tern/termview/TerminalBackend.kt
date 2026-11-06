// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.termview

import io.github.luftaquila.tern.terminal.TerminalEmulator

/** What [TerminalView] needs from a session. */
interface TerminalBackend {
    val emulator: TerminalEmulator

    /** Guards every access to [emulator]. */
    val lock: Any

    /** Non-blocking: bytes are queued for the remote host. */
    fun write(data: ByteArray)

    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int)

    /** [listener] is invoked on a background thread after the screen changed. */
    fun addScreenListener(listener: () -> Unit)

    fun removeScreenListener(listener: () -> Unit)
}
