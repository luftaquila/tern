// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pattern
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.LockSecrets
import io.github.luftaquila.tern.data.LockType
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SetupStep { VERIFY, ENTER, CONFIRM, DONE }

/**
 * Sets the app lock to [target] (or turns it off with [LockType.NONE]). An existing lock is
 * confirmed first; a new PIN or pattern is entered twice. With [enableBiometric] (the user
 * asked for fingerprint unlock and this PIN/pattern is its fallback) the fingerprint is
 * confirmed and turned on right after.
 */
@Composable
fun LockSetupScreen(target: LockType, enableBiometric: Boolean = false) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val lock = container.lock
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val shake = rememberShake()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    // The lock as it was when the screen opened.
    val current = remember { container.settings.value.let { s -> if (lock.isEnabled(s)) s.lockType else LockType.NONE } }
    val currentPinLength = remember { container.settings.value.lockPinLength }
    var step by remember { mutableStateOf(if (current != LockType.NONE) SetupStep.VERIFY else SetupStep.ENTER) }
    var firstSecret by remember { mutableStateOf<String?>(null) }
    var firstPinLength by remember { mutableIntStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var patternReset by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (current == LockType.NONE && target == LockType.NONE) navigator.pop()
    }

    val padType = if (step == SetupStep.VERIFY) current else target

    fun fail(message: String, then: suspend () -> Unit = {}) {
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
        error = message
        scope.launch {
            shake.play()
            pin = ""
            then()
        }
    }

    fun submit(secret: String, pinLength: Int) {
        if (busy) return
        when (step) {
            SetupStep.VERIFY -> {
                busy = true
                scope.launch {
                    val ok = lock.check(secret)
                    busy = false
                    if (!ok) {
                        fail(context.getString(if (current == LockType.PIN) R.string.lock_wrong_pin else R.string.lock_wrong_pattern)) {
                            delay(400)
                            patternReset++
                        }
                        return@launch
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    if (target == LockType.NONE) {
                        lock.set(LockType.NONE, null)
                        navigator.pop()
                        return@launch
                    }
                    delay(150)
                    pin = ""
                    error = null
                    patternReset++
                    step = SetupStep.ENTER
                }
            }
            SetupStep.ENTER -> {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                firstSecret = secret
                firstPinLength = pinLength
                error = null
                scope.launch {
                    delay(150)
                    pin = ""
                    patternReset++
                    step = SetupStep.CONFIRM
                }
            }
            SetupStep.CONFIRM -> {
                if (secret != firstSecret) {
                    fail(context.getString(R.string.lock_setup_mismatch)) {
                        delay(700)
                        firstSecret = null
                        patternReset++
                        step = SetupStep.ENTER
                    }
                    return
                }
                busy = true
                scope.launch {
                    lock.set(target, secret, pinLength)
                    busy = false
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    pin = ""
                    step = SetupStep.DONE
                }
            }
            SetupStep.DONE -> Unit
        }
    }

    // Steps shown in the progress bar at the top.
    val steps = buildList {
        if (current != LockType.NONE) add(SetupStep.VERIFY)
        if (target != LockType.NONE) {
            add(SetupStep.ENTER)
            add(SetupStep.CONFIRM)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (step != SetupStep.DONE && steps.size > 1) StepProgress(steps.indexOf(step), steps.size)
                }
                Spacer(Modifier.width(48.dp))
            }
            AnimatedContent(
                targetState = step == SetupStep.DONE,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "setupDone",
                modifier = Modifier.weight(1f),
            ) { done ->
                if (done) {
                    SetupDone(target, changed = current != LockType.NONE, enableBiometric, settings.lockBiometric, onFinish = { navigator.pop() })
                } else {
                    SetupInput(
                        step = step,
                        target = target,
                        padType = padType,
                        pin = pin,
                        knownLength = when (step) {
                            SetupStep.VERIFY -> currentPinLength
                            SetupStep.CONFIRM -> firstPinLength
                            else -> 0
                        },
                        error = error,
                        busy = busy,
                        shake = shake,
                        patternReset = patternReset,
                        onPin = { pin = it; error = null },
                        onPinComplete = { submit(LockSecrets.pin(it), it.length) },
                        onPatternStart = { error = null },
                        onPattern = { dots ->
                            if (step != SetupStep.VERIFY && dots.size < MIN_PATTERN_DOTS) {
                                fail(context.getString(R.string.lock_setup_too_short)) {
                                    delay(500)
                                    patternReset++
                                }
                            } else {
                                submit(LockSecrets.pattern(dots), 0)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupInput(
    step: SetupStep,
    target: LockType,
    padType: LockType,
    pin: String,
    knownLength: Int,
    error: String?,
    busy: Boolean,
    shake: Shake,
    patternReset: Int,
    onPin: (String) -> Unit,
    onPinComplete: (String) -> Unit,
    onPatternStart: () -> Unit,
    onPattern: (List<Int>) -> Unit,
) {
    val isPin = padType != LockType.PATTERN
    val title = when (step) {
        SetupStep.VERIFY -> if (isPin) R.string.lock_setup_verify_pin_title else R.string.lock_setup_verify_pattern_title
        SetupStep.ENTER -> if (isPin) R.string.lock_setup_new_pin_title else R.string.lock_setup_new_pattern_title
        else -> if (isPin) R.string.lock_setup_confirm_pin_title else R.string.lock_setup_confirm_pattern_title
    }
    val message = error ?: stringResource(
        when (step) {
            SetupStep.VERIFY -> if (target == LockType.NONE) R.string.lock_setup_verify_off_desc else R.string.lock_setup_verify_desc
            SetupStep.ENTER -> if (isPin) R.string.lock_setup_new_pin_desc else R.string.lock_setup_new_pattern_desc
            else -> R.string.lock_setup_confirm_desc
        },
    )
    val icon = when {
        step == SetupStep.VERIFY -> Icons.Rounded.Lock
        isPin -> Icons.Rounded.Pin
        else -> Icons.Rounded.Pattern
    }
    LockLayout(
        header = {
            StepIcon(icon, error = error != null)
            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "setupTitle",
            ) { t ->
                LockTitle(stringResource(t), message, error = error != null)
            }
        },
        input = {
            if (isPin) {
                PinDots(pin.length, knownLength, error != null, Modifier.shake(shake))
                Spacer(Modifier.height(36.dp))
                PinPad(
                    enabled = !busy,
                    onDigit = { d ->
                        if (pin.length < MAX_PIN) {
                            val next = pin + d
                            onPin(next)
                            if ((knownLength > 0 && next.length == knownLength) || (knownLength == 0 && next.length == MAX_PIN)) onPinComplete(next)
                        }
                    },
                    onDelete = { onPin(pin.dropLast(1)) },
                    onClear = { onPin("") },
                    modifier = Modifier.weight(1f, fill = false),
                    // A new PIN's length is up to the user: ✓ takes it once it is long enough.
                    leftKey = if (knownLength == 0) {
                        { size -> DoneKey(size, enabled = pin.length >= MIN_PIN && !busy) { onPinComplete(pin) } }
                    } else {
                        null
                    },
                )
            } else {
                PatternPad(
                    enabled = !busy,
                    error = error != null,
                    onStart = onPatternStart,
                    onDone = onPattern,
                    modifier = Modifier.weight(1f, fill = false).shake(shake),
                    resetKey = patternReset,
                )
            }
        },
    )
}

@Composable
private fun DoneKey(size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        label = "doneKey",
    )
    val content by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
        label = "doneKeyContent",
    )
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = color, contentColor = content, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, stringResource(R.string.action_done), Modifier.size(size * 0.42f))
        }
    }
}

/** Short bars for the steps (confirm current → enter → confirm new). */
@Composable
private fun StepProgress(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val color by animateColorAsState(
                if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "stepBar",
            )
            Box(Modifier.size(width = if (i == index) 28.dp else 16.dp, height = 4.dp).clip(CircleShape).background(color))
        }
    }
}

/** Success page; offers fingerprint unlock unless it is already on. */
@Composable
private fun SetupDone(target: LockType, changed: Boolean, enableBiometric: Boolean, biometricOn: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val haptic = LocalHapticFeedback.current
    val status = remember { Biometrics.status(context) }
    val fingerprint = remember { Biometrics.hasFingerprint(context) }
    var bioError by remember { mutableStateOf<String?>(null) }
    val promptTitle = stringResource(if (fingerprint) R.string.lock_biometric_confirm_fingerprint else R.string.lock_biometric_confirm_generic)
    val cancel = stringResource(R.string.action_cancel)
    fun confirmBiometrics() {
        bioError = null
        Biometrics.prompt(
            context,
            promptTitle,
            subtitle = null,
            negative = cancel,
            onSuccess = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                container.lock.setBiometric(true)
            },
            onError = { bioError = it.toString() },
        )
    }
    LaunchedEffect(Unit) {
        if (enableBiometric && !biometricOn && status == Biometrics.Status.READY) {
            delay(350)
            confirmBiometrics()
        }
    }

    val badge = remember { Animatable(0f) }
    LaunchedEffect(Unit) { badge.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = badge.value
                        scaleY = badge.value
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(
                    when {
                        !changed -> R.string.lock_setup_done_on
                        target == LockType.PIN -> R.string.lock_setup_done_pin
                        else -> R.string.lock_setup_done_pattern
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    when {
                        biometricOn && target == LockType.PIN -> R.string.lock_setup_done_desc_bio_pin
                        biometricOn -> R.string.lock_setup_done_desc_bio_pattern
                        target == LockType.PIN -> R.string.lock_setup_done_desc_pin
                        else -> R.string.lock_setup_done_desc_pattern
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (status != Biometrics.Status.UNAVAILABLE) {
                Spacer(Modifier.height(32.dp))
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(if (fingerprint) Icons.Rounded.Fingerprint else Icons.Rounded.Face, active = biometricOn)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(if (fingerprint) R.string.lock_biometric_title_fingerprint else R.string.lock_biometric_title_generic),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                bioError ?: stringResource(
                                    when {
                                        status == Biometrics.Status.NOT_ENROLLED ->
                                            if (fingerprint) R.string.lock_biometric_not_enrolled_fingerprint else R.string.lock_biometric_not_enrolled_generic
                                        biometricOn -> R.string.lock_biometric_on_desc
                                        else -> R.string.lock_biometric_offer_desc
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (bioError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            biometricOn -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                            status == Biometrics.Status.NOT_ENROLLED -> TextButton(onClick = {
                                container.lock.beforeExternalPicker()
                                Biometrics.openEnrollment(context)
                            }) { Text(stringResource(R.string.lock_biometric_enroll)) }
                            else -> FilledTonalButton(onClick = ::confirmBiometrics) { Text(stringResource(R.string.lock_biometric_enable)) }
                        }
                    }
                }
            }
        }
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.action_done), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Rounded icon tile used on the app lock screens; filled with the accent color when [active]. */
@Composable
internal fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, size: Dp = 44.dp) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "tileBg",
    )
    val fg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tileFg",
    )
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(size * 0.55f))
    }
}
