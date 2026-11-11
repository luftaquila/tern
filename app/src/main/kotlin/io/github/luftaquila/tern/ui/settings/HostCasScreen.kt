// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.HostCaEntry
import io.github.luftaquila.tern.ssh.SshCertificates
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.keys.readNBytesCompat
import io.github.luftaquila.tern.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Largest public key file read from a file. */
private const val MAX_KEY_BYTES = 64 * 1024

private const val CERT_AUTHORITY_MARKER = "@cert-authority"

/**
 * [text] as a CA key line and, for an OpenSSH known_hosts `@cert-authority` line, its host
 * patterns; the first non-empty line counts.
 */
internal fun parseAuthorityLine(text: String): Pair<String, String?> {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    if (!line.startsWith(CERT_AUTHORITY_MARKER)) return line to null
    val fields = line.removePrefix(CERT_AUTHORITY_MARKER).trim().split(Regex("\\s+"), limit = 2)
    return fields.getOrElse(1) { "" } to fields[0]
}

/** The CAs trusted for host certificates, each for the hosts its patterns cover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostCasScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val entries by container.hostCas.entries.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HostCaEntry?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_host_cas)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.host_ca_add)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.host_cas_hint),
                    // Broken between words, so no syllable is left alone on a line.
                    style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.host_cas_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    )
                }
            }
            items(entries, key = { it.id }) { entry -> AuthorityCard(entry) { editing = entry } }
        }
    }

    if (adding) AuthorityDialog(null, onDismiss = { adding = false })
    editing?.let { AuthorityDialog(it, onDismiss = { editing = null }) }
}

@Composable
private fun AuthorityCard(entry: HostCaEntry, onClick: () -> Unit) {
    val fingerprint by produceState<String?>(null, entry.publicKey) {
        value = withContext(Dispatchers.Default) { runCatching { SshCertificates.publicKeyFingerprint(entry.publicKey) }.getOrNull() }
    }
    val fields = entry.publicKey.trim().split(Regex("\\s+"), limit = 3)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.hostPatterns, style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily, maxLines = 2, overflow = TextOverflow.Ellipsis)
            fields.getOrNull(2)?.let { comment ->
                Text(comment, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(fields.firstOrNull(), fingerprint).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Adds a CA, or edits or deletes [entry]. Takes a public key line, a .pub file or an `@cert-authority` line. */
@Composable
private fun AuthorityDialog(entry: HostCaEntry?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(entry?.publicKey.orEmpty()) }
    var patterns by remember { mutableStateOf(entry?.hostPatterns.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    fun takeKey(text: String) {
        val (line, linePatterns) = parseAuthorityLine(text)
        key = if (linePatterns != null) line else text
        if (linePatterns != null && patterns.isBlank()) patterns = linePatterns
        error = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(MAX_KEY_BYTES + 1)
                if (bytes.size > MAX_KEY_BYTES) error("too large")
                takeKey(String(bytes, Charsets.UTF_8))
            }
        }.onFailure { error = context.getString(R.string.keys_read_failed) }
    }

    fun save() {
        val line = parseAuthorityLine(key).first
        working = true
        scope.launch {
            val valid = withContext(Dispatchers.Default) { runCatching { SshCertificates.publicKeyFingerprint(line) }.isSuccess }
            working = false
            if (!valid) {
                error = context.getString(R.string.host_ca_invalid)
                return@launch
            }
            val hostPatterns = patterns.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
            container.hostCas.upsert((entry ?: HostCaEntry(publicKey = line, hostPatterns = hostPatterns)).copy(publicKey = line, hostPatterns = hostPatterns))
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(if (entry == null) R.string.host_ca_add else R.string.host_ca_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = ::takeKey,
                    label = { Text(stringResource(R.string.host_ca_key)) },
                    placeholder = { Text("ssh-ed25519 AAAA… host-ca", fontFamily = MonoFamily, fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily, fontSize = 11.sp),
                    isError = error != null,
                    supportingText = { Text(error ?: stringResource(R.string.host_ca_key_hint)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp),
                )
                TextButton(onClick = {
                    container.lock.beforeExternalPicker()
                    picker.launch(arrayOf("*/*"))
                }) {
                    Icon(Icons.Rounded.FileOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keys_from_file))
                }
                OutlinedTextField(
                    value = patterns,
                    onValueChange = { patterns = it },
                    label = { Text(stringResource(R.string.host_ca_hosts)) },
                    placeholder = { Text("*.example.com", fontFamily = MonoFamily) },
                    supportingText = { Text(stringResource(R.string.host_ca_hosts_hint)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = !working && key.isNotBlank() && patterns.isNotBlank()) {
                Text(stringResource(if (entry == null) R.string.action_create else R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (entry != null) {
                    TextButton(onClick = { deleting = true }, enabled = !working) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )

    if (deleting && entry != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.host_ca_delete_title)) },
            text = { Text(stringResource(R.string.host_ca_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    container.hostCas.delete(entry.id)
                    deleting = false
                    onDismiss()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
