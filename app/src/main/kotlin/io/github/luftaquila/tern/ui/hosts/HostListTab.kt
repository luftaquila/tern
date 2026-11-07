// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.hosts

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.Host
import io.github.luftaquila.tern.data.tagNames
import io.github.luftaquila.tern.session.SessionState
import io.github.luftaquila.tern.session.TerminalSession
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.LocalRequestNotificationPermission
import io.github.luftaquila.tern.ui.Route
import io.github.luftaquila.tern.ui.theme.HostColors
import io.github.luftaquila.tern.ui.theme.MonoFamily

@Composable
fun HostListTab(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val requestNotifications = LocalRequestNotificationPermission.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Host?>(null) }

    val allTags = remember(hosts) { hosts.tagNames() }
    // A filter tag no host has any more filters nothing.
    val activeTag = tagFilter?.takeIf { t -> allTags.any { it.equals(t, ignoreCase = true) } }
    val filtered = remember(hosts, query, activeTag) {
        val q = query.trim()
        hosts
            .filter { h -> activeTag == null || h.tags.any { it.equals(activeTag, ignoreCase = true) } }
            .filter { h ->
                q.isEmpty() || h.displayName.contains(q, true) || h.hostname.contains(q, true) || h.username.contains(q, true) ||
                    h.group.contains(q, true) || h.tags.any { it.contains(q, true) }
            }
            .sortedWith(compareByDescending<Host> { it.lastUsedAt }.thenBy { it.displayName.lowercase() })
    }
    val sections = remember(filtered) { hostSections(filtered) }
    // While searching or filtering, folded groups open so no match is hidden.
    val filtering = query.isNotBlank() || activeTag != null

    fun toggleGroup(key: String) = container.settings.update { s ->
        val existing = hosts.map { it.group.lowercase() }.toSet()
        val folded = if (key in s.collapsedHostGroups) s.collapsedHostGroups - key else s.collapsedHostGroups + key
        s.copy(collapsedHostGroups = folded.filter { it in existing })
    }

    fun connect(host: Host) {
        requestNotifications()
        val session = container.sessions.open(host)
        navigator.push(Route.Terminal(session.id))
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sessions.isNotEmpty()) {
                item {
                    SectionLabel(stringResource(R.string.section_active_sessions))
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(sessions, key = { it.id }) { s ->
                            SessionChip(
                                session = s,
                                onOpen = { navigator.push(Route.Terminal(s.id)) },
                                onClose = { container.sessions.close(s.id) },
                            )
                        }
                    }
                }
            }
            if (hosts.isNotEmpty()) {
                item {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text(stringResource(R.string.search_hosts)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, null) }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                }
                if (allTags.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(allTags, key = { it }) { tag ->
                                val selected = tag.equals(activeTag, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = { tagFilter = if (selected) null else tag },
                                    label = { Text("#$tag") },
                                )
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.hosts_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            sections.forEach { section ->
                val folded = !filtering && section.key in settings.collapsedHostGroups
                if (section.key.isEmpty()) {
                    item { SectionLabel(stringResource(R.string.section_hosts)) }
                } else {
                    item(key = "group:" + section.key) {
                        GroupHeader(section.name, section.hosts.size, folded, onToggle = if (filtering) null else ({ toggleGroup(section.key) }))
                    }
                }
                if (!folded) {
                    items(section.hosts, key = { it.id }) { host ->
                        val connected = sessions.any { it.host.id == host.id }
                        HostCard(
                            host = host,
                            connected = connected,
                            onConnect = { connect(host) },
                            onSftp = {
                                requestNotifications()
                                navigator.push(Route.Sftp(host.id))
                            },
                            onEdit = { navigator.push(Route.HostEditor(host.id)) },
                            onDuplicate = {
                                container.hosts.upsert(
                                    host.copy(
                                        id = java.util.UUID.randomUUID().toString(),
                                        label = host.displayName + " (2)",
                                        createdAt = System.currentTimeMillis(),
                                        lastUsedAt = 0,
                                    ),
                                )
                            },
                            onDelete = { pendingDelete = host },
                        )
                    }
                }
            }
            if (hosts.isEmpty()) {
                item { EmptyHosts(onAdd = { navigator.push(Route.HostEditor(null)) }) }
            }
        }
        if (hosts.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { navigator.push(Route.HostEditor(null)) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.action_add_host)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }

    pendingDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_host_title)) },
            text = { Text(stringResource(R.string.delete_host_message, host.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    container.hosts.delete(host.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
    )
}

/** The hosts of one group, or ([key] empty) those without a group. */
internal class HostSection(val key: String, val name: String, val hosts: List<Host>)

/**
 * [hosts] split by group, each keeping their order: hosts without a group first, then the groups
 * by name. Group names differing only in case are one group, shown with its first host's spelling.
 */
internal fun hostSections(hosts: List<Host>): List<HostSection> {
    val byKey = hosts.groupBy { it.group.lowercase() }
    val ungrouped = byKey[""].orEmpty()
    val groups = byKey.filterKeys { it.isNotEmpty() }.map { (key, list) -> HostSection(key, list.first().group, list) }.sortedBy { it.key }
    return (if (ungrouped.isEmpty()) emptyList() else listOf(HostSection("", "", ungrouped))) + groups
}

/** A group's name and host count; tapping it folds or opens the group unless [onToggle] is null. */
@Composable
private fun GroupHeader(name: String, count: Int, folded: Boolean, onToggle: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (folded) Icons.Rounded.Folder else Icons.Rounded.FolderOpen,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        // The name shortened before the count, the arrow kept at the end.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
        if (onToggle != null) {
            Icon(
                if (folded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                stringResource(if (folded) R.string.group_expand else R.string.group_collapse),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun HostAvatar(host: Host, size: Int = 44) {
    val color = HostColors[host.colorIndex.mod(HostColors.size)]
    // Tag colors are tuned for dark surfaces; darken the letter on light surfaces for contrast.
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val letterColor = if (light) lerp(color, Color.Black, 0.45f) else color
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        val initial = host.displayName.trim().firstOrNull()?.uppercase() ?: "?"
        Text(initial, color = letterColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HostCard(
    host: Host,
    connected: Boolean,
    onConnect: () -> Unit,
    onSftp: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onConnect,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HostAvatar(host)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        host.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (connected) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    host.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (host.tags.isNotEmpty()) {
                    Text(
                        host.tags.joinToString("  ") { "#$it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onSftp) {
                Icon(Icons.Rounded.FolderOpen, stringResource(R.string.action_sftp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_duplicate)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionChip(session: TerminalSession, onOpen: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val dot = when (state) {
        SessionState.Connected -> MaterialTheme.colorScheme.secondary
        SessionState.Connecting -> MaterialTheme.colorScheme.tertiary
        is SessionState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(220.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        session.host.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close_session), modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun EmptyHosts(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.empty_hosts_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.empty_hosts_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_add_host))
        }
    }
}
