// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.terminal

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.ExtraKeyDef
import io.github.luftaquila.tern.data.ExtraKeysConfig
import io.github.luftaquila.tern.data.KeyTextEscapes
import io.github.luftaquila.tern.data.Snippet
import io.github.luftaquila.tern.data.SpecialKey
import io.github.luftaquila.tern.input.ModifierLatch
import io.github.luftaquila.tern.input.StickyModifiers
import io.github.luftaquila.tern.terminal.TerminalKey
import io.github.luftaquila.tern.ui.theme.MonoFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the extra keys do; implemented by the terminal screen. */
interface ExtraKeyActions {
    fun onKey(key: TerminalKey)
    fun onText(text: String)
    fun onRaw(text: String)
    fun onCtrl(letter: Char)
    fun onPaste()
    fun onToggleKeyboard()

    /** Opens the list of snippets to send. */
    fun onSnippets()

    /** CTRL or ALT was armed: an unfinished IME syllable must not absorb the next jamo. */
    fun onModifierArmed()

    /** Sends a text key's or snippet's [text]: typed when plain, as-is with control characters. */
    fun sendText(text: String) = if (KeyTextEscapes.isRaw(text)) onRaw(text) else onText(text)

    companion object {
        val NONE = object : ExtraKeyActions {
            override fun onKey(key: TerminalKey) {}
            override fun onText(text: String) {}
            override fun onRaw(text: String) {}
            override fun onCtrl(letter: Char) {}
            override fun onPaste() {}
            override fun onToggleKeyboard() {}
            override fun onSnippets() {}
            override fun onModifierArmed() {}
        }
    }
}

fun SpecialKey.label(): String = when (this) {
    SpecialKey.KEYBOARD -> "⌨"
    SpecialKey.PASTE -> "PASTE"
    SpecialKey.SNIPPETS -> "SNIPPETS"
    SpecialKey.ESC -> "ESC"
    SpecialKey.TAB -> "TAB"
    SpecialKey.ENTER -> "⏎"
    SpecialKey.BACKSPACE -> "⌫"
    SpecialKey.CTRL -> "CTRL"
    SpecialKey.ALT -> "ALT"
    SpecialKey.SHIFT -> "SHIFT"
    SpecialKey.LEFT -> "←"
    SpecialKey.DOWN -> "↓"
    SpecialKey.UP -> "↑"
    SpecialKey.RIGHT -> "→"
    SpecialKey.HOME -> "HOME"
    SpecialKey.END -> "END"
    SpecialKey.PAGE_UP -> "PGUP"
    SpecialKey.PAGE_DOWN -> "PGDN"
    SpecialKey.INSERT -> "INS"
    SpecialKey.DELETE -> "DEL"
    else -> ctrlLetter?.let { "^" + it.uppercaseChar() } ?: name // F1..F12
}

internal fun SpecialKey.icon(): ImageVector? = when (this) {
    SpecialKey.KEYBOARD -> Icons.Rounded.Keyboard
    SpecialKey.PASTE -> Icons.Rounded.ContentPaste
    SpecialKey.SNIPPETS -> Icons.AutoMirrored.Rounded.TextSnippet
    // The ⏎ and ⌫ glyphs are tiny in most fonts.
    SpecialKey.ENTER -> Icons.AutoMirrored.Rounded.KeyboardReturn
    SpecialKey.BACKSPACE -> Icons.AutoMirrored.Rounded.Backspace
    else -> null
}

internal fun ExtraKeyDef.icon(): ImageVector? = (this as? ExtraKeyDef.Special)?.key?.icon()

/**
 * Visible label of a key: its custom label, the text with control characters made visible, or
 * for a snippet key the name of its snippet in [snippets].
 */
fun ExtraKeyDef.label(snippets: Map<String, Snippet> = emptyMap()): String = when (this) {
    is ExtraKeyDef.Special -> key.label()
    is ExtraKeyDef.Text -> label?.takeIf { it.isNotBlank() } ?: KeyTextEscapes.display(text)
    is ExtraKeyDef.SnippetRef -> snippets[id]?.title.orEmpty()
}

private fun SpecialKey.terminalKey(): TerminalKey? = when (this) {
    SpecialKey.ESC -> TerminalKey.ESCAPE
    SpecialKey.TAB -> TerminalKey.TAB
    SpecialKey.ENTER -> TerminalKey.ENTER
    SpecialKey.BACKSPACE -> TerminalKey.BACKSPACE
    SpecialKey.LEFT -> TerminalKey.LEFT
    SpecialKey.DOWN -> TerminalKey.DOWN
    SpecialKey.UP -> TerminalKey.UP
    SpecialKey.RIGHT -> TerminalKey.RIGHT
    SpecialKey.HOME -> TerminalKey.HOME
    SpecialKey.END -> TerminalKey.END
    SpecialKey.PAGE_UP -> TerminalKey.PAGE_UP
    SpecialKey.PAGE_DOWN -> TerminalKey.PAGE_DOWN
    SpecialKey.INSERT -> TerminalKey.INSERT
    SpecialKey.DELETE -> TerminalKey.DELETE
    SpecialKey.F1 -> TerminalKey.F1
    SpecialKey.F2 -> TerminalKey.F2
    SpecialKey.F3 -> TerminalKey.F3
    SpecialKey.F4 -> TerminalKey.F4
    SpecialKey.F5 -> TerminalKey.F5
    SpecialKey.F6 -> TerminalKey.F6
    SpecialKey.F7 -> TerminalKey.F7
    SpecialKey.F8 -> TerminalKey.F8
    SpecialKey.F9 -> TerminalKey.F9
    SpecialKey.F10 -> TerminalKey.F10
    SpecialKey.F11 -> TerminalKey.F11
    SpecialKey.F12 -> TerminalKey.F12
    else -> null
}

/**
 * Sizes of the extra keys. [scale] is the user's key size setting (1 = 40 dp tall keys); the
 * bar and the editor read it through [LocalKeyMetrics] so both draw the same keys.
 */
@Immutable
class KeyMetrics(val scale: Float) {
    val height: Dp = 40.dp * scale
    val minWidth: Dp = 44.dp * scale
    val padding: Dp = 10.dp * scale
    val corner: Dp = 10.dp * scale
    val icon: Dp = 20.dp * scale
    val textLarge: TextUnit = (16f * scale).sp
    val textSmall: TextUnit = (12f * scale).sp

    override fun equals(other: Any?) = other is KeyMetrics && other.scale == scale
    override fun hashCode() = scale.hashCode()
}

val LocalKeyMetrics = staticCompositionLocalOf { KeyMetrics(1f) }

/** Space between keys in a row, between rows, and around the bar. */
private val KEY_GAP = 4.dp
private val ROW_GAP = 3.dp
private val BAR_PADDING_H = 4.dp
private val BAR_PADDING_V = 3.dp

/**
 * The configurable row(s) of keys phones lack. Row 0 is always shown; further rows appear when
 * [expanded], the first of them with an edit button ([onEdit]) under the expand toggle. Each row
 * stretches its keys to fill the width when they fit and scrolls horizontally when they don't.
 * Raw pointer input instead of clickable so tapping a key never moves focus away from the
 * terminal (which would hide the soft keyboard).
 */
@Composable
fun ExtraKeysBar(
    config: ExtraKeysConfig,
    snippets: Map<String, Snippet>,
    modifiers: StickyModifiers,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    background: Color,
    actions: ExtraKeyActions,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    onEdit: (() -> Unit)? = null,
) {
    fun press(key: ExtraKeyDef) {
        when (key) {
            is ExtraKeyDef.Special -> when (val k = key.key) {
                SpecialKey.CTRL -> {
                    modifiers.toggleCtrl()
                    if (modifiers.ctrl != ModifierLatch.OFF) actions.onModifierArmed()
                }
                SpecialKey.ALT -> {
                    modifiers.toggleAlt()
                    if (modifiers.alt != ModifierLatch.OFF) actions.onModifierArmed()
                }
                SpecialKey.SHIFT -> modifiers.toggleShift()
                SpecialKey.KEYBOARD -> actions.onToggleKeyboard()
                SpecialKey.PASTE -> actions.onPaste()
                SpecialKey.SNIPPETS -> actions.onSnippets()
                else -> {
                    val letter = k.ctrlLetter
                    if (letter != null) actions.onCtrl(letter) else k.terminalKey()?.let(actions::onKey)
                }
            }
            is ExtraKeyDef.Text -> actions.sendText(key.text)
            is ExtraKeyDef.SnippetRef -> snippets[key.id]?.let { actions.sendText(it.text) }
        }
    }

    // Keys of deleted snippets are left out.
    val rows = remember(config, snippets) {
        config.rows.map { row -> row.filter { it !is ExtraKeyDef.SnippetRef || it.id in snippets } }
    }

    val editDescription = stringResource(R.string.action_edit_extra_keys)
    CompositionLocalProvider(LocalKeyMetrics provides remember(scale) { KeyMetrics(scale) }) {
        ExtraKeysBarLayout(
            rows = rows,
            expanded = expanded,
            background = background,
            modifier = modifier,
            toggle = {
                KeyButton(
                    label = "",
                    icon = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                    onTap = { onExpandedChange(!expanded) },
                )
            },
            edit = onEdit?.let { action ->
                { KeyButton(label = "", icon = Icons.Rounded.Edit, description = editDescription, onTap = action) }
            },
        ) { key ->
            val special = (key as? ExtraKeyDef.Special)?.key
            val latch = when (special) {
                SpecialKey.CTRL -> modifiers.ctrl
                SpecialKey.ALT -> modifiers.alt
                SpecialKey.SHIFT -> modifiers.shift
                else -> ModifierLatch.OFF
            }
            KeyButton(
                label = key.label(snippets),
                icon = special?.icon(),
                latch = latch,
                repeat = special?.repeatable == true,
                onTap = { press(key) },
            )
        }
    }
}

/**
 * The bar's layout, shared with the extra-keys editor so the editor shows the bar exactly as it
 * appears above the keyboard: row 0 with [toggle] on its right, then (when [expanded]) the other
 * rows, the first of them with [edit] on its right.
 *
 * @param rowModifier applied to each row's viewport (the part of the row that is on screen).
 * @param rowScroll the horizontal scroll state of each row.
 * @param rowOverlay drawn over each row's viewport.
 */
@Composable
fun <T> ExtraKeysBarLayout(
    rows: List<List<T>>,
    expanded: Boolean,
    background: Color,
    toggle: @Composable () -> Unit,
    edit: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    rowModifier: (Int) -> Modifier = { Modifier },
    rowScroll: @Composable (Int) -> ScrollState = { rememberScrollState() },
    rowOverlay: @Composable BoxScope.(Int) -> Unit = {},
    key: @Composable (T) -> Unit,
) {
    @Composable
    fun BarRow(index: Int, trailing: (@Composable () -> Unit)?) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).then(rowModifier(index))) {
                KeyRow(rows.getOrElse(index) { emptyList() }, scrollState = rowScroll(index), content = key)
                rowOverlay(index)
            }
            if (trailing != null) {
                Spacer(Modifier.width(KEY_GAP))
                trailing()
            }
        }
    }

    Column(modifier.fillMaxWidth().background(background).padding(horizontal = BAR_PADDING_H, vertical = BAR_PADDING_V)) {
        BarRow(0, if (rows.size > 1) toggle else null)
        if (rows.size > 1) {
            val rest = @Composable {
                Column {
                    for (i in 1 until rows.size) {
                        Spacer(Modifier.height(ROW_GAP))
                        BarRow(i, if (i == 1) edit else null)
                    }
                }
            }
            if (animate) {
                AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) { rest() }
            } else if (expanded) {
                rest()
            }
        }
    }
}

/**
 * One row of keys. Keys that fit are widened to fill the row exactly, each up to
 * [MAX_STRETCH] times the basic key width (a row too short even then is centered); keys that
 * don't fit keep their natural widths and the row scrolls sideways.
 */
@Composable
fun <T> KeyRow(
    keys: List<T>,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable (T) -> Unit,
) {
    val metrics = LocalKeyMetrics.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewport = constraints.maxWidth
        val density = LocalDensity.current
        val gap = with(density) { KEY_GAP.roundToPx() }
        val height = with(density) { metrics.height.roundToPx() }
        val stretchLimit = with(density) { (metrics.minWidth * MAX_STRETCH).roundToPx() }
        Layout(
            content = { keys.forEach { content(it) } },
            modifier = Modifier.horizontalScroll(scrollState),
        ) { measurables, _ ->
            val preferred = measurables.map { it.maxIntrinsicWidth(height) }
            val gaps = gap * (preferred.size - 1).coerceAtLeast(0)
            val widths = stretchToFill(preferred, viewport - gaps, stretchLimit)
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], height)) }
            val used = placeables.sumOf { it.width } + gaps
            val width = maxOf(viewport, used)
            layout(width, height) {
                var x = (viewport - used).coerceAtLeast(0) / 2
                placeables.forEach { p ->
                    p.placeRelative(x, 0)
                    x += p.width + gap
                }
            }
        }
    }
}

/** How far a key may be widened to fill its row, in basic key widths. */
private const val MAX_STRETCH = 2.5f

/**
 * Widens [natural] widths to add up to [available], growing every key by the same amount but
 * none beyond [limit] (keys already wider than that keep their width). Returns [natural] when
 * the keys don't fit at all; the sum stays below [available] only when every key hit the limit.
 */
internal fun stretchToFill(natural: List<Int>, available: Int, limit: Int): List<Int> {
    val widths = natural.toMutableList()
    var remaining = available - natural.sum()
    if (widths.isEmpty() || remaining <= 0) return widths
    var growing = widths.indices.filter { widths[it] < limit }
    while (remaining > 0 && growing.isNotEmpty()) {
        val share = remaining / growing.size
        if (share == 0) {
            // Fewer pixels left than growing keys: one more pixel for the first ones.
            for (i in growing.take(remaining)) widths[i]++
            remaining = 0
            break
        }
        for (i in growing) {
            val grow = minOf(share, limit - widths[i])
            widths[i] += grow
            remaining -= grow
        }
        growing = growing.filter { widths[it] < limit }
    }
    return widths
}

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L

/**
 * A bar key. It fires when the finger lifts, not on touch-down, so a touch that turns into a
 * sideways scroll of the row (which cancels the press) sends nothing. Holding a [repeat] key
 * fires it after [REPEAT_DELAY_MS] and then every [REPEAT_INTERVAL_MS] until released.
 */
@Composable
fun KeyButton(
    label: String,
    icon: ImageVector? = null,
    latch: ModifierLatch = ModifierLatch.OFF,
    repeat: Boolean = false,
    description: String? = null,
    onTap: () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val bg = when {
        latch == ModifierLatch.LOCKED -> colors.primary
        latch == ModifierLatch.ONCE -> colors.primaryContainer
        pressed -> colors.surfaceContainerHighest
        else -> colors.surfaceContainerHigh
    }
    val fg = when (latch) {
        ModifierLatch.LOCKED -> colors.onPrimary
        ModifierLatch.ONCE -> colors.onPrimaryContainer
        ModifierLatch.OFF -> colors.onSurface
    }
    KeyCap(
        label = label,
        icon = icon,
        description = description,
        background = bg,
        contentColor = fg,
        modifier = Modifier.pointerInput(repeat) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    var repeated = false
                    val repeater: Job? = if (repeat) {
                        scope.launch {
                            delay(REPEAT_DELAY_MS)
                            repeated = true
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            while (true) {
                                currentOnTap()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                    } else {
                        null
                    }
                    // False when the press was cancelled: the row scrolled or the finger left the key.
                    val released = tryAwaitRelease()
                    repeater?.cancel()
                    pressed = false
                    if (released && !repeated) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnTap()
                    }
                },
            )
        },
    )
}

/** How a key looks. Shared by the bar and the extra-keys editor so the editor shows the real keys. */
@Composable
fun KeyCap(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    description: String? = null,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val metrics = LocalKeyMetrics.current
    Box(
        modifier
            .height(metrics.height)
            .defaultMinSize(minWidth = metrics.minWidth)
            .clip(RoundedCornerShape(metrics.corner))
            .background(background)
            .padding(horizontal = metrics.padding),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, description, tint = contentColor, modifier = Modifier.size(metrics.icon))
        } else {
            Text(
                label,
                color = contentColor,
                fontFamily = MonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = if (label.length > 2) metrics.textSmall else metrics.textLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
