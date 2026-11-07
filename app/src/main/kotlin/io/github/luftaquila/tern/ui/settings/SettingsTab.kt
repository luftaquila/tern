// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.settings

import io.github.luftaquila.tern.data.LockType
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.data.ThemeMode
import io.github.luftaquila.tern.terminal.ColorScheme
import io.github.luftaquila.tern.terminal.ColorSchemes
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.Route
import io.github.luftaquila.tern.ui.backup.ExportFlow
import io.github.luftaquila.tern.ui.backup.ImportFlow
import io.github.luftaquila.tern.ui.theme.MonoFamily
import kotlin.math.roundToInt

@Composable
fun SettingsTab(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    fun update(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )

        SettingsSection(stringResource(R.string.settings_display)) {
            SettingLabel(stringResource(R.string.settings_theme))
            Segmented(
                options = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.DARK to R.string.theme_dark, ThemeMode.LIGHT to R.string.theme_light),
                selected = settings.themeMode,
                onSelect = { mode -> update { it.copy(themeMode = mode) } },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_desc), settings.dynamicColor) { v ->
                    update { it.copy(dynamicColor = v) }
                }
            }
            SettingLabel(stringResource(R.string.settings_color_scheme))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(ColorSchemes.ALL, key = { it.id }) { scheme ->
                    SchemePreview(scheme, selected = scheme.id == settings.colorSchemeId) {
                        update { it.copy(colorSchemeId = scheme.id) }
                        container.sessions.applyColorScheme(scheme.id)
                    }
                }
            }
            SettingLabel(stringResource(R.string.settings_font))
            FontRow(settings) { navigator.push(Route.Fonts) }
            SwitchRow(stringResource(R.string.settings_cursor_blink), null, settings.cursorBlink) { v -> update { it.copy(cursorBlink = v) } }
        }

        SettingsSection(stringResource(R.string.settings_input)) {
            SwitchRow(stringResource(R.string.settings_ime_incognito), stringResource(R.string.settings_ime_incognito_desc), settings.imeIncognito) { v ->
                update { it.copy(imeIncognito = v) }
            }
            SettingLabel(stringResource(R.string.settings_backspace))
            Segmented(
                options = listOf(false to null, true to null),
                selected = settings.backspaceSendsCtrlH,
                labelOf = { if (it) "^H (0x08)" else "DEL (0x7F)" },
                onSelect = { v -> update { it.copy(backspaceSendsCtrlH = v) } },
            )
        }

        SettingsSection(stringResource(R.string.settings_extra_keys_section)) {
            SwitchRow(stringResource(R.string.settings_extra_keys), stringResource(R.string.settings_extra_keys_desc), settings.showExtraKeys) { v ->
                update { it.copy(showExtraKeys = v) }
            }
            NavRow(stringResource(R.string.settings_extra_keys_config), stringResource(R.string.settings_extra_keys_config_desc)) {
                navigator.push(Route.ExtraKeysEditor)
            }
            NavRow(stringResource(R.string.snippets_title), stringResource(R.string.settings_snippets_desc)) {
                navigator.push(Route.Snippets)
            }
        }

        SettingsSection(stringResource(R.string.settings_terminal)) {
            var speed by remember(settings.scrollSpeed) { mutableFloatStateOf(settings.scrollSpeed) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingLabel(stringResource(R.string.settings_scroll_speed), Modifier.weight(1f))
                Text("${(speed * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = speed,
                onValueChange = { speed = it },
                onValueChangeFinished = { update { it.copy(scrollSpeed = (speed * 10).roundToInt() / 10f) } },
                valueRange = AppSettings.MIN_SCROLL_SPEED..AppSettings.MAX_SCROLL_SPEED,
                steps = ((AppSettings.MAX_SCROLL_SPEED - AppSettings.MIN_SCROLL_SPEED) / AppSettings.SCROLL_SPEED_STEP).roundToInt() - 1,
            )
            SettingLabel(stringResource(R.string.settings_scrollback))
            Segmented(
                options = listOf(1000 to null, 5000 to null, 10000 to null, 20000 to null),
                selected = settings.scrollbackLines,
                labelOf = { "%,d".format(it) },
                onSelect = { n -> update { it.copy(scrollbackLines = n) } },
            )
            SwitchRow(stringResource(R.string.settings_keep_screen_on), null, settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
            SwitchRow(stringResource(R.string.settings_bell_vibrate), null, settings.vibrateOnBell) { v -> update { it.copy(vibrateOnBell = v) } }
            SwitchRow(stringResource(R.string.settings_inline_images), stringResource(R.string.settings_inline_images_desc), settings.advertiseInlineImages) { v ->
                update { it.copy(advertiseInlineImages = v) }
            }
        }

        SettingsSection(stringResource(R.string.settings_security)) {
            NavRow(stringResource(R.string.settings_app_lock), appLockSummary(settings)) { navigator.push(Route.AppLock) }
            NavRow(stringResource(R.string.settings_known_hosts), stringResource(R.string.settings_known_hosts_desc)) {
                navigator.push(Route.KnownHosts)
            }
            SwitchRow(stringResource(R.string.settings_pq_kex), stringResource(R.string.settings_pq_kex_desc), settings.postQuantumKex) { v ->
                update { it.copy(postQuantumKex = v) }
            }
        }

        SettingsSection(stringResource(R.string.backup_section)) {
            NavRow(stringResource(R.string.backup_export), stringResource(R.string.backup_export_row)) { exporting = true }
            NavRow(stringResource(R.string.backup_import), stringResource(R.string.backup_import_row)) { importing = true }
        }

        SettingsSection(stringResource(R.string.donate_section)) {
            NavRow(stringResource(R.string.donate_title), stringResource(R.string.donate_summary)) { navigator.push(Route.Donate) }
        }

        SettingsSection(stringResource(R.string.settings_about)) {
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
            }
            NavRow(stringResource(R.string.settings_version), version, onClick = null)
            NavRow(stringResource(R.string.settings_licenses), null) { navigator.push(Route.Licenses) }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (exporting) ExportFlow { exporting = false }
    if (importing) ImportFlow { importing = false }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SettingLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier.padding(top = 4.dp))
}

@Composable
private fun <T> Segmented(
    options: List<Pair<T, Int?>>,
    selected: T,
    onSelect: (T) -> Unit,
    labelOf: ((T) -> String)? = null,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) {
                Text(if (label != null) stringResource(label) else labelOf?.invoke(value) ?: value.toString(), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(title: String, description: String?, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!description.isNullOrEmpty()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SchemePreview(scheme: ColorScheme, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color(scheme.background),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(128.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("$ ls -la", color = Color(scheme.foreground), fontFamily = MonoFamily, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in 1..6) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(scheme.ansi[i])))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(scheme.name, color = Color(scheme.foreground).copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** "Off", or the method in use ("PIN and fingerprint"). */
@Composable
private fun appLockSummary(settings: AppSettings): String {
    val context = LocalContext.current
    val container = LocalContainer.current
    if (!container.lock.isEnabled(settings)) return stringResource(R.string.app_lock_summary_off)
    val method = stringResource(if (settings.lockType == LockType.PATTERN) R.string.lock_type_pattern else R.string.lock_type_pin)
    val bio = settings.lockBiometric && remember { io.github.luftaquila.tern.ui.lock.Biometrics.available(context) }
    if (!bio) return method
    val fingerprint = remember { io.github.luftaquila.tern.ui.lock.Biometrics.hasFingerprint(context) }
    return stringResource(R.string.app_lock_summary_bio, method, stringResource(if (fingerprint) R.string.lock_biometric_fingerprint else R.string.lock_biometric_generic))
}
