// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.lock

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pattern
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.data.LockType
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.Route

/**
 * App lock settings: status, the unlock method (PIN or pattern), fingerprint unlock and when to
 * lock again. Fingerprint is always offered; picking it without a lock first sets up the PIN or
 * pattern it falls back to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lock = container.lock
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val enabled = lock.isEnabled(settings)
    // Re-checked whenever the screen comes back (a fingerprint may have been enrolled meanwhile).
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val bio = remember(lifecycleState) { Biometrics.status(context) }
    val fingerprint = remember { Biometrics.hasFingerprint(context) }
    val bioOn = enabled && settings.lockBiometric && bio == Biometrics.Status.READY
    var chooseFallback by remember { mutableStateOf(false) }
    var bioError by remember { mutableStateOf<String?>(null) }

    val confirmTitle = stringResource(if (fingerprint) R.string.lock_biometric_confirm_fingerprint else R.string.lock_biometric_confirm_generic)
    val cancel = stringResource(R.string.action_cancel)
    fun setBiometric(on: Boolean) {
        bioError = null
        when {
            !on -> lock.setBiometric(false)
            bio == Biometrics.Status.NOT_ENROLLED -> {
                lock.beforeExternalPicker()
                Biometrics.openEnrollment(context)
            }
            bio != Biometrics.Status.READY -> Unit
            !enabled -> chooseFallback = true
            else -> Biometrics.prompt(
                context,
                confirmTitle,
                subtitle = null,
                negative = cancel,
                onSuccess = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    lock.setBiometric(true)
                },
                onError = { bioError = it.toString() },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_app_lock)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(enabled, statusText(settings, enabled, bioOn, fingerprint))

            GroupLabel(stringResource(R.string.app_lock_method))
            MethodCard(
                icon = Icons.Rounded.Pin,
                title = stringResource(R.string.lock_type_pin),
                description = stringResource(if (enabled && settings.lockType == LockType.PIN) R.string.lock_method_in_use else R.string.lock_method_pin_desc),
                selected = enabled && settings.lockType == LockType.PIN,
            ) { navigator.push(Route.LockSetup(LockType.PIN)) }
            MethodCard(
                icon = Icons.Rounded.Pattern,
                title = stringResource(R.string.lock_type_pattern),
                description = stringResource(if (enabled && settings.lockType == LockType.PATTERN) R.string.lock_method_in_use else R.string.lock_method_pattern_desc),
                selected = enabled && settings.lockType == LockType.PATTERN,
            ) { navigator.push(Route.LockSetup(LockType.PATTERN)) }

            GroupLabel(stringResource(if (fingerprint) R.string.app_lock_fingerprint else R.string.app_lock_biometrics))
            BiometricCard(
                fingerprint = fingerprint,
                status = bio,
                checked = bioOn,
                lockEnabled = enabled,
                error = bioError,
                onChange = ::setBiometric,
            )

            if (enabled) {
                GroupLabel(stringResource(R.string.settings_lock_after))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val options = listOf(
                            0 to stringResource(R.string.lock_after_now),
                            60 to stringResource(R.string.lock_after_1m),
                            300 to stringResource(R.string.lock_after_5m),
                            900 to stringResource(R.string.lock_after_15m),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { i, (seconds, label) ->
                                SegmentedButton(
                                    selected = settings.lockAfterSeconds == seconds,
                                    onClick = { container.settings.update { it.copy(lockAfterSeconds = seconds) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                    icon = {},
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { navigator.push(Route.LockSetup(LockType.NONE)) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                ) { Text(stringResource(R.string.lock_setup_title_off)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (chooseFallback) {
        ModalBottomSheet(
            onDismissRequest = { chooseFallback = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconTile(if (fingerprint) Icons.Rounded.Fingerprint else Icons.Rounded.Face, active = true, size = 56.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.lock_fallback_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.lock_fallback_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                MethodCard(
                    Icons.Rounded.Pin,
                    stringResource(R.string.lock_type_pin),
                    stringResource(R.string.lock_method_pin_desc),
                    selected = false,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    chooseFallback = false
                    navigator.push(Route.LockSetup(LockType.PIN, enableBiometric = true))
                }
                Spacer(Modifier.height(10.dp))
                MethodCard(
                    Icons.Rounded.Pattern,
                    stringResource(R.string.lock_type_pattern),
                    stringResource(R.string.lock_method_pattern_desc),
                    selected = false,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    chooseFallback = false
                    navigator.push(Route.LockSetup(LockType.PATTERN, enableBiometric = true))
                }
            }
        }
    }
}

/** One-line summary of the lock, also used by the settings list. */
@Composable
fun statusText(settings: AppSettings, enabled: Boolean, bioOn: Boolean, fingerprint: Boolean): String {
    if (!enabled) return stringResource(R.string.app_lock_off_desc)
    val bioName = stringResource(if (fingerprint) R.string.lock_biometric_fingerprint else R.string.lock_biometric_generic)
    return when {
        settings.lockType == LockType.PATTERN && bioOn -> stringResource(R.string.app_lock_on_bio_pattern, bioName)
        settings.lockType == LockType.PATTERN -> stringResource(R.string.app_lock_on_pattern)
        bioOn -> stringResource(R.string.app_lock_on_bio_pin, bioName, settings.lockPinLength)
        else -> stringResource(R.string.app_lock_on_pin, settings.lockPinLength)
    }
}

@Composable
private fun StatusCard(enabled: Boolean, text: String) {
    val container by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "statusCard",
    )
    val onContainer = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(shape = RoundedCornerShape(28.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (enabled) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    null,
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(if (enabled) R.string.app_lock_on_title else R.string.app_lock_off_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = onContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = onContainer.copy(alpha = 0.78f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
    )
}

@Composable
private fun MethodCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = color,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, active = selected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BiometricCard(
    fingerprint: Boolean,
    status: Biometrics.Status,
    checked: Boolean,
    lockEnabled: Boolean,
    error: String?,
    onChange: (Boolean) -> Unit,
) {
    val usable = status != Biometrics.Status.UNAVAILABLE
    Surface(
        onClick = { onChange(!checked) },
        enabled = usable,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (checked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(if (fingerprint) Icons.Rounded.Fingerprint else Icons.Rounded.Face, active = checked)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (fingerprint) R.string.lock_biometric_title_fingerprint else R.string.lock_biometric_title_generic),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        error ?: stringResource(
                            when {
                                status == Biometrics.Status.UNAVAILABLE -> R.string.lock_biometric_unsupported
                                status == Biometrics.Status.NOT_ENROLLED ->
                                    if (fingerprint) R.string.lock_biometric_not_enrolled_fingerprint else R.string.lock_biometric_not_enrolled_generic
                                checked -> R.string.lock_biometric_on_desc
                                lockEnabled -> R.string.lock_biometric_offer_desc
                                else -> R.string.lock_biometric_needs_fallback
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, onCheckedChange = onChange, enabled = status == Biometrics.Status.READY)
            }
            if (status == Biometrics.Status.NOT_ENROLLED) {
                TextButton(onClick = { onChange(true) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.lock_biometric_enroll))
                }
            }
        }
    }
}
