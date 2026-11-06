// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.keys

import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.SecretBox
import io.github.luftaquila.tern.data.SshKeyEntry
import io.github.luftaquila.tern.ssh.KeyPassphraseException
import io.github.luftaquila.tern.ssh.SshKeys
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.describeError
import io.github.luftaquila.tern.ui.lock.ConfirmLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Exports [key] as an OpenSSH private key file: the app lock first (when it is on), then a
 * passphrase for the file, then the system's save dialog. The key never goes to the clipboard
 * or to other apps. [onFinished] runs when the export is done or abandoned.
 */
@Composable
internal fun ExportKeyFlow(key: SshKeyEntry, onFinished: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var confirmed by remember { mutableStateOf(!container.lock.isEnabled()) }
    // The finished file, kept only while the save dialog is open (never in saved state).
    var pending by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val text = pending
        pending = null
        if (uri == null) {
            onFinished()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    if (text == null) error("export lost") // the app was recreated meanwhile
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.onFailure {
                    // Leave no empty or partial key file behind.
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }.isSuccess
            }
            Toast.makeText(context, if (saved) R.string.keys_export_done else R.string.keys_export_failed, Toast.LENGTH_SHORT).show()
            onFinished()
        }
    }

    when {
        !confirmed -> ConfirmLock(
            container.lock,
            settings,
            title = stringResource(R.string.keys_export),
            onConfirmed = { confirmed = true },
            onCancel = onFinished,
        )
        pending == null -> ExportKeyDialog(
            key,
            onExport = { text ->
                pending = text
                container.lock.beforeExternalPicker()
                save.launch(fileName(key))
            },
            onDismiss = onFinished,
        )
    }
}

/** OpenSSH's usual file names, so the file can go straight into ~/.ssh. */
private fun fileName(key: SshKeyEntry): String = when {
    key.keyType == "ssh-ed25519" -> "id_ed25519"
    key.keyType.startsWith("ecdsa-") -> "id_ecdsa"
    key.keyType == "ssh-rsa" -> "id_rsa"
    else -> "id_key"
}

@Composable
private fun ExportKeyDialog(key: SshKeyEntry, onExport: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val privateKey = remember(key) { SecretBox.decrypt(key.encryptedPrivateKey) }
    val stored = remember(key) { SecretBox.decryptOrNull(key.encryptedPassphrase) }
    // Asked for when the key is encrypted and its passphrase was not saved (or no longer fits).
    var askCurrent by remember(key) { mutableStateOf(stored == null && SshKeys.isEncrypted(privateKey)) }
    var current by remember { mutableStateOf("") }
    var protect by remember { mutableStateOf(true) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val mismatch = protect && confirmation.isNotEmpty() && confirmation != passphrase
    val ready = !working && (!askCurrent || current.isNotEmpty()) && (!protect || (passphrase.isNotEmpty() && passphrase == confirmation))

    fun export() {
        working = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    SshKeys.exportOpenSsh(privateKey, if (askCurrent) current else stored, key.name, passphrase.takeIf { protect })
                }
            }
            working = false
            result.onSuccess(onExport).onFailure { e ->
                if (e is KeyPassphraseException) askCurrent = true
                error = describeError(context, e)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_export)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Balanced lines broken between words, so no syllable is left alone on the last line.
                Text(stringResource(R.string.keys_export_warning), style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading))
                if (askCurrent) {
                    PassphraseField(current, { current = it }, stringResource(R.string.keys_export_current))
                }
                Row(
                    Modifier.fillMaxWidth().clickable { protect = !protect },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.keys_export_protect), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = protect, onCheckedChange = { protect = it })
                }
                if (protect) {
                    PassphraseField(passphrase, { passphrase = it }, stringResource(R.string.field_passphrase))
                    PassphraseField(
                        confirmation,
                        { confirmation = it },
                        stringResource(R.string.keys_export_confirm),
                        error = if (mismatch) stringResource(R.string.keys_export_mismatch) else null,
                    )
                } else {
                    Text(
                        stringResource(R.string.keys_export_unprotected),
                        style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::export, enabled = ready) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.keys_export_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
