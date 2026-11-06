// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.ui.LocalContainer
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.session.PendingPrompt
import io.github.luftaquila.tern.ssh.HostKeyStatus
import io.github.luftaquila.tern.ui.theme.MonoFamily
import kotlinx.coroutines.delay

/**
 * Renders whatever the SSH layer is currently asking. While the app is locked the question waits:
 * a dialog is a window of its own and would show above the lock.
 */
@Composable
fun PromptDialog(prompt: PendingPrompt?) {
    val locked by LocalContainer.current.lock.locked.collectAsStateWithLifecycle()
    if (locked) return
    when (prompt) {
        null -> Unit
        is PendingPrompt.HostKey -> HostKeyDialog(prompt)
        is PendingPrompt.Password -> PasswordDialog(prompt)
        is PendingPrompt.Passphrase -> PassphraseDialog(prompt)
        is PendingPrompt.KeyboardInteractive -> KeyboardInteractiveDialog(prompt)
    }
}

@Composable
private fun HostKeyDialog(prompt: PendingPrompt.HostKey) {
    val changed = prompt.status == HostKeyStatus.CHANGED
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = {
            Icon(
                if (changed) Icons.Rounded.Warning else Icons.Rounded.Shield, null,
                tint = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(if (changed) R.string.hostkey_changed_title else R.string.hostkey_new_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        if (changed) R.string.hostkey_changed_message else R.string.hostkey_new_message,
                        "${prompt.info.host}:${prompt.info.port}",
                    ),
                )
                FingerprintBox(prompt.info.keyType, prompt.info.fingerprintSha256)
                if (changed && prompt.previous.isNotEmpty()) {
                    Text(stringResource(R.string.hostkey_previous), style = MaterialTheme.typography.labelLarge)
                    prompt.previous.forEach { p ->
                        FingerprintBox(p.keyType, fingerprintOf(p.publicKeyBase64))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { prompt.answer(true) },
                colors = if (changed) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) {
                Text(stringResource(if (changed) R.string.hostkey_replace else R.string.hostkey_trust))
            }
        },
        dismissButton = { TextButton(onClick = { prompt.answer(false) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun FingerprintBox(keyType: String, fingerprint: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(keyType, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily)
        }
    }
}

/** OpenSSH-style SHA256 fingerprint of a base64 wire-format key. */
fun fingerprintOf(publicKeyBase64: String): String = runCatching {
    val blob = android.util.Base64.decode(publicKeyBase64, android.util.Base64.DEFAULT)
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
    "SHA256:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
}.getOrDefault("?")

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onDone: () -> Unit,
    visibleByDefault: Boolean = false,
    focus: FocusRequester? = null,
) {
    var visible by remember { mutableStateOf(visibleByDefault) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (visibleByDefault) KeyboardType.Text else KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
            }
        },
        modifier = Modifier.fillMaxWidth().let { m -> if (focus != null) m.focusRequester(focus) else m },
    )
}

@Composable
private fun AutoFocus(focus: FocusRequester) {
    LaunchedEffect(focus) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun PasswordDialog(prompt: PendingPrompt.Password) {
    var password by remember(prompt) { mutableStateOf("") }
    var save by remember(prompt) { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    AutoFocus(focus)
    val submit = { prompt.answer(password, save) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text(stringResource(R.string.prompt_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${prompt.target.username}@${prompt.target.host}",
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (prompt.attempt > 1) {
                    Text(stringResource(R.string.prompt_password_wrong), color = MaterialTheme.colorScheme.error)
                }
                SecretField(password, { password = it }, stringResource(R.string.field_password), { submit() }, focus = focus)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = save, onCheckedChange = { save = it })
                    Text(stringResource(R.string.prompt_save_password))
                }
            }
        },
        confirmButton = { Button(onClick = { submit() }) { Text(stringResource(R.string.action_connect)) } },
        dismissButton = { TextButton(onClick = { prompt.cancel() }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PassphraseDialog(prompt: PendingPrompt.Passphrase) {
    var value by remember(prompt) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AutoFocus(focus)
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text(stringResource(R.string.prompt_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.prompt_passphrase_message, prompt.keyName))
                if (prompt.attempt > 1) {
                    Text(stringResource(R.string.prompt_passphrase_wrong), color = MaterialTheme.colorScheme.error)
                }
                SecretField(value, { value = it }, stringResource(R.string.field_passphrase), { prompt.answer(value) }, focus = focus)
            }
        },
        confirmButton = { Button(onClick = { prompt.answer(value) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = { prompt.cancel() }) { Text(stringResource(R.string.action_skip)) } },
    )
}

@Composable
private fun KeyboardInteractiveDialog(prompt: PendingPrompt.KeyboardInteractive) {
    val answers = remember(prompt) { mutableStateListOf<String>().apply { repeat(prompt.prompts.size) { add("") } } }
    val focus = remember { FocusRequester() }
    if (prompt.prompts.isNotEmpty()) AutoFocus(focus)
    val submit = { prompt.answer(answers.toList()) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text(prompt.name.ifBlank { stringResource(R.string.prompt_ki_title) }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt.instruction.isNotBlank()) Text(prompt.instruction)
                prompt.prompts.forEachIndexed { i, p ->
                    SecretField(
                        value = answers[i],
                        onValueChange = { answers[i] = it },
                        label = p.text.trim().ifEmpty { stringResource(R.string.prompt_ki_response) },
                        onDone = { if (i == prompt.prompts.lastIndex) submit() },
                        visibleByDefault = p.echo,
                        focus = if (i == 0) focus else null,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { submit() }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = { prompt.cancel() }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
