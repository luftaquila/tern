// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.backup

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.luftaquila.tern.data.Backup
import io.github.luftaquila.tern.data.BackupFile
import io.github.luftaquila.tern.data.WrongPasswordException
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.describeError
import io.github.luftaquila.tern.ui.lock.ConfirmLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** Largest file an import reads. */
private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

/**
 * Exports hosts and snippets (and, with a file password, keys and saved passwords) to a file:
 * the app lock first (when it is on), then the options, then the system's save dialog.
 * [onFinished] runs when the export is done or abandoned.
 */
@Composable
fun ExportFlow(onFinished: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var confirmed by remember { mutableStateOf(!container.lock.isEnabled()) }
    // The finished file, kept only while the save dialog is open (never in saved state).
    var pending by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
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
                    // Leave no empty or partial file behind.
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }.isSuccess
            }
            Toast.makeText(context, if (saved) R.string.backup_export_done else R.string.keys_export_failed, Toast.LENGTH_SHORT).show()
            onFinished()
        }
    }

    when {
        !confirmed -> ConfirmLock(
            container.lock,
            settings,
            title = stringResource(R.string.backup_export),
            onConfirmed = { confirmed = true },
            onCancel = onFinished,
        )
        pending == null -> ExportDialog(
            onExport = { text ->
                pending = text
                container.lock.beforeExternalPicker()
                save.launch("tern-backup-${LocalDate.now()}.json")
            },
            onDismiss = onFinished,
        )
    }
}

@Composable
private fun ExportDialog(onExport: (String) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var withSecrets by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val mismatch = withSecrets && confirmation.isNotEmpty() && confirmation != password
    val ready = !working && (!withSecrets || (password.isNotEmpty() && password == confirmation))

    fun export() {
        working = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { container.backups.export(password.takeIf { withSecrets }) }
            }
            working = false
            result.onSuccess(onExport).onFailure { error = describeError(context, it) }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.backup_export)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.backup_export_desc), style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading))
                Row(
                    Modifier.fillMaxWidth().clickable { withSecrets = !withSecrets },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.backup_include_secrets), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = withSecrets, onCheckedChange = { withSecrets = it })
                }
                if (withSecrets) {
                    PasswordField(password, { password = it }, stringResource(R.string.backup_password))
                    PasswordField(
                        confirmation,
                        { confirmation = it },
                        stringResource(R.string.backup_password_confirm),
                        error = if (mismatch) stringResource(R.string.backup_password_mismatch) else null,
                    )
                    Note(stringResource(R.string.backup_password_note))
                } else {
                    Note(stringResource(R.string.backup_without_secrets))
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
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Imports an export file: the system's file picker, then what the file holds and, when it has
 * keys and passwords, the file password. [onFinished] runs when the import is done or abandoned.
 */
@Composable
fun ImportFlow(onFinished: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<BackupFile?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onFinished()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > MAX_IMPORT_BYTES) error("too large")
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                    Backup.decode(text)
                }
            }
            read.onSuccess { file = it }.onFailure {
                Toast.makeText(context, R.string.backup_read_failed, Toast.LENGTH_SHORT).show()
                onFinished()
            }
        }
    }
    var launched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!launched) {
            launched = true
            container.lock.beforeExternalPicker()
            open.launch(arrayOf("*/*"))
        }
    }
    file?.let { ImportDialog(it, onFinished) }
}

@Composable
private fun ImportDialog(file: BackupFile, onFinished: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var wrongPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun import() {
        working = true
        error = null
        wrongPassword = false
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val secrets = file.secrets?.takeIf { password.isNotEmpty() }?.let { Backup.open(it, password) }
                    container.backups.import(file, secrets)
                }
            }
            working = false
            result.onSuccess {
                Toast.makeText(context, R.string.backup_import_done, Toast.LENGTH_SHORT).show()
                onFinished()
            }.onFailure { e ->
                if (e is WrongPasswordException) wrongPassword = true else error = describeError(context, e)
            }
        }
    }

    val res = context.resources
    val hosts = res.getQuantityString(R.plurals.backup_hosts, file.hosts.size, file.hosts.size)
    val snippets = res.getQuantityString(R.plurals.backup_snippets, file.snippets.size, file.snippets.size)
    AlertDialog(
        onDismissRequest = { if (!working) onFinished() },
        title = { Text(stringResource(R.string.backup_import)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.backup_import_contents, hosts, snippets),
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                )
                if (file.secrets != null) {
                    Note(stringResource(R.string.backup_import_secrets))
                    PasswordField(
                        password,
                        {
                            password = it
                            wrongPassword = false
                        },
                        stringResource(R.string.backup_password),
                        error = if (wrongPassword) stringResource(R.string.backup_wrong_password) else null,
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::import, enabled = !working) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.backup_import))
            }
        },
        dismissButton = { TextButton(onClick = onFinished, enabled = !working) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
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
