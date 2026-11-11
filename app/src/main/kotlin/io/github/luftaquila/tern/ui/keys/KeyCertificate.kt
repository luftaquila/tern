// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.keys

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.SshKeyEntry
import io.github.luftaquila.tern.ssh.CertificateInfo
import io.github.luftaquila.tern.ssh.SshCertificates
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Largest certificate file read from a file. */
private const val MAX_CERTIFICATE_BYTES = 64 * 1024

/** [certificate] read off the main thread (parsing first sets up the crypto providers); null while reading or when unreadable. */
@Composable
internal fun rememberCertificateInfo(certificate: String?): CertificateInfo? {
    val info by produceState<CertificateInfo?>(null, certificate) {
        value = certificate?.let { withContext(Dispatchers.Default) { runCatching { SshCertificates.inspect(it) }.getOrNull() } }
    }
    return info
}

internal enum class CertificateState { VALID, NOT_YET_VALID, EXPIRED }

internal fun CertificateInfo.stateNow(): CertificateState {
    val now = System.currentTimeMillis()
    val after = validAfterMillis
    return when {
        isValidAt(now) -> CertificateState.VALID
        after != null && now < after -> CertificateState.NOT_YET_VALID
        else -> CertificateState.EXPIRED
    }
}

/** A small label on a key: it has a certificate, and whether that certificate is valid now. */
@Composable
internal fun CertificateBadge(certificate: String) {
    val state = rememberCertificateInfo(certificate)?.stateNow() ?: CertificateState.VALID
    val valid = state == CertificateState.VALID
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(6.dp), color = if (valid) colors.primaryContainer else colors.errorContainer) {
        Text(
            stringResource(
                when (state) {
                    CertificateState.VALID -> R.string.keys_certificate
                    CertificateState.NOT_YET_VALID -> R.string.keys_certificate_badge_not_yet
                    CertificateState.EXPIRED -> R.string.keys_certificate_badge_expired
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (valid) colors.onPrimaryContainer else colors.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** The certificate in a key's details: who it lets in, how long, and its CA. */
@Composable
internal fun CertificateSummary(certificate: String) {
    val info = rememberCertificateInfo(certificate)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    fun date(millis: Long) = format.format(Date(millis))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.keys_certificate), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            CertificateBadgeFor(info)
        }
        if (info == null) return@Column
        Text(
            if (info.principals.isEmpty()) stringResource(R.string.keys_certificate_any_user)
            else stringResource(R.string.keys_certificate_users, info.principals.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
        )
        val after = info.validAfterMillis
        val before = info.validBeforeMillis
        Text(
            when {
                after != null && before != null -> stringResource(R.string.keys_certificate_valid_range, date(after), date(before))
                before != null -> stringResource(R.string.keys_certificate_valid_until, date(before))
                after != null -> stringResource(R.string.keys_certificate_valid_from, date(after))
                else -> stringResource(R.string.keys_certificate_no_expiry)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (info.stateNow() == CertificateState.VALID) muted else MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.keys_certificate_ca, info.caFingerprintSha256),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
            color = muted,
        )
    }
}

/** Nothing while valid; otherwise why not, once [info] is read. */
@Composable
private fun CertificateBadgeFor(info: CertificateInfo?) {
    val state = info?.stateNow() ?: return
    if (state == CertificateState.VALID) return
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            stringResource(if (state == CertificateState.NOT_YET_VALID) R.string.keys_certificate_not_yet else R.string.keys_certificate_expired),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Adds or replaces [key]'s certificate, pasted or read from a file; only a user certificate of this key is taken. */
@Composable
internal fun CertificateDialog(key: SshKeyEntry, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(MAX_CERTIFICATE_BYTES + 1)
                if (bytes.size > MAX_CERTIFICATE_BYTES) error("too large")
                text = String(bytes, Charsets.UTF_8)
                error = null
            }
        }.onFailure { error = context.getString(R.string.keys_read_failed) }
    }

    fun save() {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return
        working = true
        scope.launch {
            val info = withContext(Dispatchers.Default) { runCatching { SshCertificates.inspect(line) }.getOrNull() }
            working = false
            val problem = when {
                info == null -> R.string.keys_certificate_not_certificate
                info.isHostCertificate -> R.string.keys_certificate_host
                info.keyFingerprintSha256 != key.fingerprint -> R.string.keys_certificate_wrong_key
                else -> null
            }
            if (problem != null) {
                error = context.getString(problem)
                return@launch
            }
            container.keys.setCertificate(key.id, line)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(if (key.certificate == null) R.string.keys_certificate_add else R.string.keys_certificate_replace)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.keys_certificate_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.keys_certificate)) },
                    placeholder = { Text("ssh-ed25519-cert-v01@openssh.com AAAA…", fontFamily = MonoFamily, fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily, fontSize = 11.sp),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
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
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = !working && text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Up to [limit] bytes; InputStream.readNBytes needs API 33. */
internal fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
