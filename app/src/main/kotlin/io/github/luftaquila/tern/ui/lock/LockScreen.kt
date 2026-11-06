// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.data.LockSecrets
import io.github.luftaquila.tern.data.LockType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Covers the whole app while it is locked. Bottom sheets and dialogs are windows of their own
 * above the app's window, so the pad is a window too ([LockWindow]), created after them and
 * therefore above them; an opaque cover in the app's window hides the content in the moment
 * before that window shows and takes the focus from the terminal. Back leaves the app.
 */
@Composable
fun LockScreen(lock: AppLock, settings: AppSettings) {
    val activity = LocalContext.current.findActivity()
    val cover = remember { FocusRequester() }
    LaunchedEffect(Unit) { cover.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(cover)
            .focusable()
            .pointerInput(Unit) { detectTapGestures { } },
    )
    LockWindow {
        LockPrompt(
            lock = lock,
            settings = settings,
            title = stringResource(R.string.lock_title),
            biometricTitle = stringResource(R.string.lock_biometric_prompt_title, stringResource(R.string.app_name)),
            verify = lock::unlock,
            onBiometric = lock::unlockedByBiometrics,
            onBack = { activity?.moveTaskToBack(true) },
        )
    }
}

/**
 * Asks for the app lock (PIN, pattern or fingerprint, as on [LockScreen]) before a sensitive
 * action, over the whole screen. Back or the system back gesture cancels.
 */
@Composable
fun ConfirmLock(lock: AppLock, settings: AppSettings, title: String, onConfirmed: () -> Unit, onCancel: () -> Unit) {
    LockWindow {
        LockPrompt(
            lock = lock,
            settings = settings,
            title = title,
            biometricTitle = title,
            verify = { secret -> lock.confirm(secret).also { ok -> if (ok) onConfirmed() } },
            onBiometric = onConfirmed,
            onBack = onCancel,
        )
    }
}

/**
 * A full-screen window for the lock pad. Back is left to the pad ([LockPrompt] handles it); the
 * system bar icons follow the pad's background, since this window draws behind the bars.
 */
@Composable
private fun LockWindow(content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        content()
    }
}

/** The activity behind [this] context; dialog content gets a wrapped one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The lock pad: [verify] checks a PIN or pattern (and acts on success), [onBiometric] runs after
 * a successful fingerprint or face check. Wrong attempts shake the pad and lockouts come from [lock].
 */
@Composable
private fun LockPrompt(
    lock: AppLock,
    settings: AppSettings,
    title: String,
    biometricTitle: String,
    verify: suspend (String) -> Boolean,
    onBiometric: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    val shake = rememberShake()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var patternReset by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lockedOut = lock.lockedOutUntil > now
    LaunchedEffect(lock.lockedOutUntil) {
        while (SystemClock.elapsedRealtime() < lock.lockedOutUntil) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
        now = SystemClock.elapsedRealtime()
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        focus.requestFocus()
        activity?.window?.let { WindowCompat.getInsetsController(it, view).hide(WindowInsetsCompat.Type.ime()) }
    }

    val isPattern = settings.lockType == LockType.PATTERN
    val fingerprint = remember { Biometrics.hasFingerprint(context) }
    val biometrics = settings.lockBiometric && remember { Biometrics.available(context) }
    val promptNegative = stringResource(if (isPattern) R.string.lock_use_pattern else R.string.lock_use_pin)
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    fun promptBiometrics() {
        signal?.cancel()
        error = null
        signal = Biometrics.prompt(
            context,
            biometricTitle,
            subtitle = null,
            negative = promptNegative,
            onSuccess = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onBiometric()
            },
            onError = { message -> error = message.toString() },
        )
    }
    LaunchedEffect(biometrics) { if (biometrics) promptBiometrics() }
    DisposableEffect(Unit) { onDispose { signal?.cancel() } }

    fun attempt(secret: String) {
        if (checking || lockedOut) return
        checking = true
        scope.launch {
            val ok = verify(secret)
            checking = false
            if (ok) return@launch
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            val wrong = context.getString(if (isPattern) R.string.lock_wrong_pattern else R.string.lock_wrong_pin)
            val left = lock.attemptsLeft
            error = if (left in 1..3) {
                "$wrong. " + context.resources.getQuantityString(R.plurals.lock_tries_left, left, left)
            } else {
                wrong
            }
            shake.play()
            pin = ""
            if (isPattern) {
                delay(500)
                patternReset++
            }
        }
    }

    val hint = when {
        lockedOut -> stringResource(R.string.lock_wait, ((lock.lockedOutUntil - now + 999) / 1000).toInt())
        error != null -> error!!
        biometrics && fingerprint -> stringResource(if (isPattern) R.string.lock_draw_pattern_or_fingerprint else R.string.lock_enter_pin_or_fingerprint)
        isPattern -> stringResource(R.string.lock_draw_pattern)
        else -> stringResource(R.string.lock_enter_pin)
    }
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) { detectTapGestures { } }, // nothing reaches the app underneath
    ) {
        LockLayout(
            modifier = Modifier
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(glow, Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.2f),
                            radius = size.maxDimension * 0.55f,
                        ),
                    )
                }
                .systemBarsPadding(),
            header = {
                AppMark()
                Spacer(Modifier.height(20.dp))
                LockTitle(title, hint, error = lockedOut || error != null)
            },
            input = {
                val enabled = !lockedOut && !checking
                if (isPattern) {
                    PatternPad(
                        enabled = enabled,
                        dimmed = lockedOut,
                        error = error != null && !lockedOut,
                        onStart = { error = null },
                        onDone = { dots -> attempt(LockSecrets.pattern(dots)) },
                        modifier = Modifier.weight(1f, fill = false).shake(shake),
                        resetKey = patternReset,
                    )
                    if (biometrics) {
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = ::promptBiometrics,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Icon(if (fingerprint) Icons.Rounded.Fingerprint else Icons.Rounded.Face, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (fingerprint) R.string.lock_biometric_title_fingerprint else R.string.lock_biometric_title_generic))
                        }
                    }
                } else {
                    PinDots(pin.length, settings.lockPinLength, error != null && !lockedOut, Modifier.shake(shake))
                    Spacer(Modifier.height(36.dp))
                    PinPad(
                        enabled = enabled,
                        onDigit = { d ->
                            if (pin.length < MAX_PIN) {
                                error = null
                                pin += d
                                if (pin.length == settings.lockPinLength) attempt(LockSecrets.pin(pin))
                            }
                        },
                        onDelete = { pin = pin.dropLast(1) },
                        onClear = { pin = "" },
                        modifier = Modifier.weight(1f, fill = false),
                        dimmed = lockedOut,
                        leftKey = if (biometrics) {
                            { size -> BiometricKey(size, fingerprint, ::promptBiometrics) }
                        } else {
                            null
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun BiometricKey(size: Dp, fingerprint: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (fingerprint) Icons.Rounded.Fingerprint else Icons.Rounded.Face,
                stringResource(if (fingerprint) R.string.lock_biometric_title_fingerprint else R.string.lock_biometric_title_generic),
                Modifier.size(size * 0.46f),
            )
        }
    }
}

/**
 * Lock and setup screens: the header above the input (pad near the thumb) on tall screens,
 * side by side on wide, short ones (landscape phones). The input column is measured with the
 * space left, so the pads shrink to fit.
 */
@Composable
internal fun LockLayout(
    header: @Composable ColumnScope.() -> Unit,
    input: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth > maxHeight && maxHeight < 560.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, content = header)
                Spacer(Modifier.width(24.dp))
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    content = input,
                )
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                header()
                Spacer(Modifier.height(32.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, content = input)
                Spacer(Modifier.weight(0.45f))
            }
        }
    }
}
