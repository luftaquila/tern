// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.terminal

/**
 * Sink for bytes the emulator must send back to the host (DA/DSR/CPR replies, OSC color
 * query answers, ...). Invoked synchronously from [TerminalEmulator.feed]; implementations
 * must not block (enqueue to a writer thread instead).
 */
fun interface TerminalOutput {
    fun write(data: ByteArray)
}

/**
 * Events raised by the emulator. All callbacks are invoked synchronously on the thread that
 * calls [TerminalEmulator.feed] / [TerminalEmulator.resize], while the caller holds whatever
 * lock it uses to guard the emulator.
 */
interface TerminalListener {
    /** OSC 0/2 window title change. */
    fun onTitleChanged(title: String) {}

    /** BEL (0x07) received outside of an OSC/DCS string. */
    fun onBell() {}

    /** OSC 52 clipboard write request (payload already base64-decoded as UTF-8). */
    fun onClipboardWrite(text: String) {}

    /** Effective palette changed (OSC 4/10/11/12/104/110/111/112, or colorScheme set). */
    fun onColorsChanged() {}

    /** OSC 7 working directory report; [path] is the percent-decoded path of the file:// URL. */
    fun onWorkingDirectoryChanged(path: String) {}
}

enum class CursorShape { BLOCK, UNDERLINE, BAR }

/** DECSET 9 / 1000 / 1002 / 1003. */
enum class MouseTrackingMode { NONE, X10, NORMAL, BUTTON_EVENT, ANY_EVENT }

enum class MouseAction { PRESS, RELEASE, MOTION, WHEEL_UP, WHEEL_DOWN }

object MouseButton {
    const val LEFT = 0
    const val MIDDLE = 1
    const val RIGHT = 2
    const val NONE = 3
}

/** Non-character keys understood by [TerminalEmulator.encodeKey]. */
enum class TerminalKey {
    UP, DOWN, LEFT, RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    BACKSPACE, TAB, ENTER, ESCAPE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** Modifier bit flags. The xterm modifier parameter is `1 + flags`. */
object KeyMod {
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4
}
