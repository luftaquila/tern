// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.luftaquila.tern.R
import kotlinx.coroutines.launch

internal const val MIN_PIN = 4
internal const val MAX_PIN = 8
internal const val MIN_PATTERN_DOTS = 4

// ---- shake ------------------------------------------------------------------------------

/** Horizontal shake played on wrong input; apply with [Modifier.shake]. */
class Shake {
    internal val offset = Animatable(0f)

    suspend fun play() {
        offset.snapTo(0f)
        offset.animateTo(
            0f,
            keyframes {
                durationMillis = 420
                -14f at 40
                14f at 100
                -10f at 160
                10f at 220
                -5f at 290
                5f at 350
            },
        )
    }
}

@Composable
fun rememberShake(): Shake = remember { Shake() }

fun Modifier.shake(shake: Shake): Modifier = offset { IntOffset(shake.offset.value.dp.roundToPx(), 0) }

// ---- header -----------------------------------------------------------------------------

/** The launcher icon: the adaptive icon's foreground on its background color, cropped like a launcher does. */
@Composable
fun AppMark(size: Dp = 64.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        // Launchers show the middle 72 of the 108 dp adaptive icon.
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 108f / 72f))
    }
}

/** Icon in a tinted circle, heading the setup steps. */
@Composable
fun StepIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, error: Boolean = false) {
    val bg by animateColorAsState(
        if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        label = "stepIconBg",
    )
    val fg by animateColorAsState(
        if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        label = "stepIconFg",
    )
    Box(Modifier.size(64.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(30.dp))
    }
}

/** Title plus a message line that cross-fades when it changes and turns red for errors. */
@Composable
fun LockTitle(title: String, message: String, error: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        val color by animateColorAsState(
            if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            label = "lockMessageColor",
        )
        AnimatedContent(
            targetState = message,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "lockMessage",
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                textAlign = TextAlign.Center,
                minLines = 2,
            )
        }
    }
}

// ---- PIN --------------------------------------------------------------------------------

/**
 * One dot per digit. With [length] 0 (a new PIN, length not chosen yet) there are as many
 * slots as digits, but at least [MIN_PIN].
 */
@Composable
fun PinDots(entered: Int, length: Int, error: Boolean, modifier: Modifier = Modifier) {
    val slots = if (length > 0) length else entered.coerceIn(MIN_PIN, MAX_PIN)
    Row(
        modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(slots) { i -> PinDot(filled = i < entered, error = error) }
    }
}

@Composable
private fun PinDot(filled: Boolean, error: Boolean) {
    val fill by animateFloatAsState(
        if (filled) 1f else 0f,
        spring(dampingRatio = 0.45f, stiffness = 800f),
        label = "pinDot",
    )
    val accent by animateColorAsState(
        if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "pinDotColor",
    )
    val ring = if (error) accent else MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(14.dp)) {
        val r = size.minDimension / 2f
        drawCircle(ring, radius = r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        if (fill > 0.01f) drawCircle(accent, radius = r * fill.coerceAtMost(1.15f))
    }
}

/**
 * Number pad sized to fit its space (keys up to 76 dp). [leftKey] fills the empty slot left of
 * 0 (biometrics on the lock screen, "done" when choosing a PIN). Holding ⌫ clears everything.
 * [dimmed] greys it out (locked out); [enabled] only stops input (e.g. while checking).
 */
@Composable
fun PinPad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    leftKey: (@Composable (size: Dp) -> Unit)? = null,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val gapH = 28.dp
        val gapV = 14.dp
        var key = 76.dp
        if (constraints.hasBoundedWidth) key = min(key, (maxWidth - gapH * 2) / 3)
        if (constraints.hasBoundedHeight) key = min(key, (maxHeight - gapV * 3) / 4)
        key = key.coerceAtLeast(44.dp)
        val alpha by animateFloatAsState(if (dimmed) 0.38f else 1f, label = "padAlpha")
        Column(Modifier.graphicsLayer { this.alpha = alpha }, verticalArrangement = Arrangement.spacedBy(gapV)) {
            for (row in listOf("123", "456", "789")) {
                Row(horizontalArrangement = Arrangement.spacedBy(gapH)) {
                    row.forEach { d -> DigitKey(d, key, enabled) { onDigit(d) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gapH), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(key), contentAlignment = Alignment.Center) { leftKey?.invoke(key) }
                DigitKey('0', key, enabled) { onDigit('0') }
                DeleteKey(key, enabled, onDelete, onClear)
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(stiffness = 1500f), label = "keyScale")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        interactionSource = interaction,
        modifier = Modifier.size(size).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit.toString(), fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
private fun DeleteKey(size: Dp, enabled: Boolean, onDelete: () -> Unit, onClear: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val description = stringResource(R.string.lock_delete_digit)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClear()
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onDelete()
                },
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.Backspace, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
    }
}

// ---- pattern ----------------------------------------------------------------------------

/**
 * 3×3 pattern grid, square, up to 300 dp. Dots are numbered 0..8 row by row; crossing a dot
 * that lies straight between two connected ones picks it up as well, as on Android's lock
 * screen. The drawn pattern stays until the next touch or until [resetKey] changes.
 */
@Composable
fun PatternPad(
    enabled: Boolean,
    error: Boolean,
    onStart: () -> Unit,
    onDone: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    resetKey: Int = 0,
) {
    val dots = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val pops = remember { List(9) { Animatable(0f) } }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDone by rememberUpdatedState(onDone)

    fun clear() {
        dots.clear()
        finger = null
        scope.launch { pops.forEach { it.snapTo(0f) } }
    }
    LaunchedEffect(resetKey) { if (resetKey != 0) clear() }

    val accent by animateColorAsState(
        if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "patternAccent",
    )
    val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val alpha by animateFloatAsState(if (dimmed) 0.38f else 1f, label = "patternAlpha")

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        var side = 300.dp
        if (constraints.hasBoundedWidth) side = min(side, maxWidth)
        if (constraints.hasBoundedHeight) side = min(side, maxHeight)
        Canvas(
            Modifier
                .size(side)
                .graphicsLayer { this.alpha = alpha }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val cell = size.width / 3f
                    fun center(i: Int) = Offset(cell * (i % 3) + cell / 2, cell * (i / 3) + cell / 2)
                    fun add(i: Int) {
                        dots.add(i)
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        scope.launch {
                            pops[i].snapTo(0f)
                            pops[i].animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
                        }
                    }
                    fun visit(p: Offset) {
                        finger = p
                        val hit = (0 until 9).firstOrNull { it !in dots && (center(it) - p).getDistance() <= cell * 0.32f } ?: return
                        dots.lastOrNull()?.let { last -> middle(last, hit)?.takeIf { it !in dots }?.let(::add) }
                        add(hit)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        clear()
                        currentOnStart()
                        visit(down.position)
                        drag(down.id) { change ->
                            change.consume()
                            visit(change.position)
                        }
                        finger = null
                        if (dots.isNotEmpty()) currentOnDone(dots.toList())
                    }
                },
        ) {
            val cell = size.width / 3f
            fun center(i: Int) = Offset(cell * (i % 3) + cell / 2, cell * (i / 3) + cell / 2)
            if (dots.isNotEmpty()) {
                val path = Path().apply {
                    val first = center(dots[0])
                    moveTo(first.x, first.y)
                    for (k in 1 until dots.size) center(dots[k]).let { lineTo(it.x, it.y) }
                    finger?.let { lineTo(it.x, it.y) }
                }
                drawPath(
                    path,
                    accent.copy(alpha = 0.7f),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            val idleRadius = 5.dp.toPx()
            for (i in 0 until 9) {
                val p = pops[i].value
                val c = center(i)
                if (i in dots) {
                    drawCircle(accent.copy(alpha = 0.18f), radius = lerp(idleRadius, 26.dp.toPx(), p.coerceAtLeast(0f)), center = c)
                    drawCircle(accent, radius = lerp(idleRadius, 9.dp.toPx(), p.coerceAtLeast(0f)), center = c)
                } else {
                    drawCircle(idle, radius = idleRadius, center = c)
                }
            }
        }
    }
}

/** The dot straight between [a] and [b] (same row, column or diagonal, one apart), if any. */
private fun middle(a: Int, b: Int): Int? {
    val (ar, ac) = a / 3 to a % 3
    val (br, bc) = b / 3 to b % 3
    if ((ar + br) % 2 != 0 || (ac + bc) % 2 != 0) return null
    val m = (ar + br) / 2 * 3 + (ac + bc) / 2
    return m.takeIf { it != a && it != b }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
