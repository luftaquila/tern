// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.KnownHostEntry
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.terminal.fingerprintOf
import io.github.luftaquila.tern.ui.theme.MonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostsScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val entries by container.knownHosts.entries.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<KnownHostEntry?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_known_hosts)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.known_hosts_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(entries.sortedBy { it.host }, key = { "${it.host}:${it.port}:${it.keyType}" }) { e ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (e.port == 22) e.host else "${e.host}:${e.port}", style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily)
                            Text(e.keyType, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                fingerprintOf(e.publicKeyBase64),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MonoFamily,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { pending = e }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
    }
    pending?.let { e ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.known_hosts_delete_title)) },
            text = { Text(stringResource(R.string.known_hosts_delete_message, e.host)) },
            confirmButton = {
                TextButton(onClick = {
                    container.knownHosts.delete(e)
                    pending = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private data class License(val name: String, val license: String, val asset: String)

private val LICENSES = listOf(
    // The app itself: NOTICE (copyright, GPL section 7 terms) followed by LICENSE, kept in sync with the repository root.
    License("Tern (luftaquila)", "GNU GPL v3.0 only, with additional terms", "licenses/Tern.txt"),
    License("D2Coding font (NAVER)", "SIL Open Font License 1.1", "licenses/D2Coding-OFL.txt"),
    License("Jetendard", "SIL Open Font License 1.1", "licenses/Jetendard-OFL.txt"),
    License("JetBrains Mono", "SIL Open Font License 1.1", "licenses/JetBrainsMono-OFL.txt"),
    License("Fira Code", "SIL Open Font License 1.1", "licenses/FiraCode-OFL.txt"),
    License("Cascadia Code (Microsoft)", "SIL Open Font License 1.1", "licenses/CascadiaCode-OFL.txt"),
    License("Source Code Pro (Adobe)", "SIL Open Font License 1.1", "licenses/SourceCodePro-OFL.txt"),
    License("IBM Plex Mono", "SIL Open Font License 1.1", "licenses/IBMPlexMono-OFL.txt"),
    License("Hack", "MIT License + Bitstream Vera License", "licenses/Hack-LICENSE.txt"),
    License("Meslo LG", "Apache License 2.0", "licenses/Meslo-LICENSE.txt"),
    License("Iosevka (Renzhi Li)", "SIL Open Font License 1.1", "licenses/Iosevka-OFL.txt"),
    License("0xProto (0xType)", "SIL Open Font License 1.1", "licenses/0xProto-OFL.txt"),
    License("Symbols Nerd Font (Nerd Fonts)", "MIT + icon set licenses (CC BY 4.0, MIT, Apache 2.0, OFL 1.1)", "licenses/NerdFonts.txt"),
    License("Noto Sans Symbols 2 (The Noto Project Authors)", "SIL Open Font License 1.1", "licenses/NotoSansSymbols2-OFL.txt"),
    License("sshj", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    License("Bouncy Castle", "MIT (Bouncy Castle License)", "licenses/BouncyCastle.txt"),
    License("SLF4J", "MIT License", "licenses/SLF4J.txt"),
    License("ASN-One (hierynomus)", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    License("AndroidX / Jetpack Compose", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    License("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    License("Material Icons", "Apache License 2.0", "licenses/Apache-2.0.txt"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    var shown by remember { mutableStateOf<License?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LICENSES) { l ->
                Surface(
                    onClick = { shown = l },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(l.name, style = MaterialTheme.typography.titleSmall)
                        Text(l.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    shown?.let { l ->
        val text = remember(l) {
            runCatching { context.assets.open(l.asset).bufferedReader().use { it.readText() } }.getOrDefault(l.license)
        }
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(l.name) },
            text = {
                Text(
                    text,
                    fontFamily = MonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
