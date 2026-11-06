// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.luftaquila.tern.terminal.KeyMod

enum class ModifierLatch { OFF, ONCE, LOCKED }

/**
 * CTRL/ALT toggles of the extra-keys row. One tap arms the modifier for the next key, a second
 * tap locks it until tapped again. Backed by Compose state so the key row re-renders.
 */
class StickyModifiers {
    var ctrl by mutableStateOf(ModifierLatch.OFF)
    var alt by mutableStateOf(ModifierLatch.OFF)
    var shift by mutableStateOf(ModifierLatch.OFF)

    /** CTRL or ALT armed: the next typed character becomes a control/meta key. */
    val isActive: Boolean get() = ctrl != ModifierLatch.OFF || alt != ModifierLatch.OFF

    /** Any modifier armed, including SHIFT (which only affects special keys and bar text). */
    val isAnyActive: Boolean get() = isActive || shift != ModifierLatch.OFF

    fun bits(): Int =
        (if (ctrl != ModifierLatch.OFF) KeyMod.CTRL else 0) or
            (if (alt != ModifierLatch.OFF) KeyMod.ALT else 0) or
            (if (shift != ModifierLatch.OFF) KeyMod.SHIFT else 0)

    /** Called after a key used the modifiers: one-shot latches are released. */
    fun consume() {
        if (ctrl == ModifierLatch.ONCE) ctrl = ModifierLatch.OFF
        if (alt == ModifierLatch.ONCE) alt = ModifierLatch.OFF
        if (shift == ModifierLatch.ONCE) shift = ModifierLatch.OFF
    }

    fun toggleShift() {
        shift = next(shift)
    }

    fun toggleCtrl() {
        ctrl = next(ctrl)
    }

    fun toggleAlt() {
        alt = next(alt)
    }

    fun clear() {
        ctrl = ModifierLatch.OFF
        alt = ModifierLatch.OFF
        shift = ModifierLatch.OFF
    }

    private fun next(m: ModifierLatch) = when (m) {
        ModifierLatch.OFF -> ModifierLatch.ONCE
        ModifierLatch.ONCE -> ModifierLatch.LOCKED
        ModifierLatch.LOCKED -> ModifierLatch.OFF
    }
}
