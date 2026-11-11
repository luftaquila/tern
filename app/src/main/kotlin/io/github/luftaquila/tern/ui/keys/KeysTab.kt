// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CardMembership
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.SecretBox
import io.github.luftaquila.tern.data.SshKeyEntry
import io.github.luftaquila.tern.ssh.KeyPassphraseException
import io.github.luftaquila.tern.ssh.SshKeyType
import io.github.luftaquila.tern.ssh.SshKeys
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KeysTab(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val keys by container.keys.keys.collectAsStateWithLifecycle()
    var addMenu by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                    Text(stringResource(R.string.tab_keys), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.keys_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(keys, key = { it.id }) { key ->
                Card(
                    onClick = { selectedId = key.id },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(key.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                    Text(key.keyType, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                                key.certificate?.let {
                                    Spacer(Modifier.width(6.dp))
                                    CertificateBadge(it)
                                }
                            }
                            Text(
                                key.fingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (keys.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.keys_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { generating = true }) { Text(stringResource(R.string.keys_generate)) }
                            OutlinedButton(onClick = { importing = true }) { Text(stringResource(R.string.keys_import)) }
                        }
                    }
                }
            }
        }
        if (keys.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(stringResource(R.string.keys_add)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.keys_generate)) },
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                        onClick = { addMenu = false; generating = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.keys_import)) },
                        leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                        onClick = { addMenu = false; importing = true },
                    )
                }
            }
        }
    }

    if (generating) GenerateKeyDialog(onDismiss = { generating = false })
    if (importing) ImportKeyDialog(onDismiss = { importing = false })
    // The current entry, so the sheet shows a certificate added from it.
    keys.firstOrNull { it.id == selectedId }?.let { key -> KeyDetailsSheet(key, onDismiss = { selectedId = null }) }
}

@Composable
private fun GenerateKeyDialog(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(android.os.Build.MODEL.lowercase().replace(' ', '-')) }
    var type by remember { mutableStateOf(SshKeyType.ED25519) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.keys_generate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_key_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SshKeyType.entries.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy) { type = t },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t }, enabled = !busy)
                        Column {
                            Text(t.displayName)
                            if (t == SshKeyType.ED25519) {
                                Text(stringResource(R.string.keys_recommended), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val key = withContext(Dispatchers.Default) { SshKeys.generate(type, name.trim()) }
                            val entry = withContext(Dispatchers.Default) {
                                SshKeyEntry(
                                    name = name.trim(),
                                    keyType = key.publicKey.substringBefore(' '),
                                    publicKey = key.publicKey,
                                    fingerprint = key.fingerprintSha256,
                                    encryptedPrivateKey = SecretBox.encrypt(key.privateKey),
                                )
                            }
                            container.keys.add(entry)
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var savePassphrase by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val encrypted = remember(text) { text.isNotBlank() && runCatching { SshKeys.isEncrypted(text) }.getOrDefault(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > 64 * 1024) error("too large")
                    text = String(bytes, Charsets.UTF_8)
                }
                if (name.isBlank()) {
                    name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: ""
                }
            }.onFailure { error = context.getString(R.string.keys_read_failed) }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.keys_import)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_key_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    label = { Text(stringResource(R.string.field_private_key)) },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", fontFamily = MonoFamily, fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily, fontSize = 11.sp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                )
                TextButton(onClick = {
                    container.lock.beforeExternalPicker()
                    picker.launch(arrayOf("*/*"))
                }) {
                    Icon(Icons.Rounded.FileOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keys_from_file))
                }
                if (encrypted) {
                    OutlinedTextField(
                        value = passphrase, onValueChange = { passphrase = it; error = null },
                        label = { Text(stringResource(R.string.field_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = savePassphrase, onCheckedChange = { savePassphrase = it })
                        Text(stringResource(R.string.keys_save_passphrase))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val pem = text.trim() + "\n"
                            val info = withContext(Dispatchers.Default) { SshKeys.inspect(pem, passphrase.ifEmpty { null }) }
                            val entry = withContext(Dispatchers.Default) {
                                SshKeyEntry(
                                    name = name.trim().ifEmpty { info.keyType },
                                    keyType = info.keyType,
                                    publicKey = info.publicKey,
                                    fingerprint = info.fingerprintSha256,
                                    encryptedPrivateKey = SecretBox.encrypt(pem),
                                    encryptedPassphrase = if (encrypted && savePassphrase && passphrase.isNotEmpty()) SecretBox.encrypt(passphrase) else null,
                                )
                            }
                            container.keys.add(entry)
                            onDismiss()
                        } catch (e: KeyPassphraseException) {
                            error = context.getString(R.string.error_key_passphrase)
                            busy = false
                        } catch (e: Exception) {
                            error = context.getString(R.string.keys_invalid, e.message ?: "")
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.action_import)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyDetailsSheet(key: SshKeyEntry, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var certifying by remember { mutableStateOf(false) }
    var uncertifying by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(key.name, style = MaterialTheme.typography.titleLarge)
                Text(key.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.keys_public_key), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    SelectionContainer {
                        Text(
                            key.publicKey,
                            fontFamily = MonoFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                        )
                    }
                }
                Text(
                    stringResource(R.string.keys_authorized_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                key.certificate?.let {
                    Spacer(Modifier.height(12.dp))
                    CertificateSummary(it)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DetailAction(Icons.Rounded.ContentCopy, stringResource(R.string.keys_copy_public)) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ssh public key", key.publicKey))
                onDismiss()
            }
            DetailAction(Icons.Rounded.Share, stringResource(R.string.keys_share_public)) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key.publicKey)
                context.startActivity(Intent.createChooser(send, key.name))
            }
            DetailAction(Icons.Rounded.FileDownload, stringResource(R.string.keys_export)) { exporting = true }
            DetailAction(
                Icons.Rounded.CardMembership,
                stringResource(if (key.certificate == null) R.string.keys_certificate_add else R.string.keys_certificate_replace),
            ) { certifying = true }
            if (key.certificate != null) {
                DetailAction(Icons.Rounded.Delete, stringResource(R.string.keys_certificate_remove), danger = true) { uncertifying = true }
            }
            DetailAction(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.sftp_rename)) { renaming = true }
            DetailAction(Icons.Rounded.Delete, stringResource(R.string.action_delete), danger = true) { deleting = true }
        }
    }
    if (exporting) ExportKeyFlow(key, onFinished = { exporting = false })
    if (certifying) CertificateDialog(key, onDismiss = { certifying = false })
    if (uncertifying) {
        AlertDialog(
            onDismissRequest = { uncertifying = false },
            title = { Text(stringResource(R.string.keys_certificate_remove_title)) },
            text = { Text(stringResource(R.string.keys_certificate_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    container.keys.setCertificate(key.id, null)
                    uncertifying = false
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { uncertifying = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (renaming) {
        var value by remember { mutableStateOf(key.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.sftp_rename)) },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    container.keys.rename(key.id, value.trim())
                    renaming = false
                    onDismiss()
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.keys_delete_title)) },
            text = { Text(stringResource(R.string.keys_delete_message, key.name)) },
            confirmButton = {
                TextButton(onClick = {
                    container.keys.delete(key.id)
                    container.hosts.detachKey(key.id)
                    deleting = false
                    onDismiss()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DetailAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    )
}
