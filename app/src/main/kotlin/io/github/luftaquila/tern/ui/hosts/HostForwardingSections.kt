// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.ForwardRule
import io.github.luftaquila.tern.data.ForwardType
import io.github.luftaquila.tern.data.Host
import io.github.luftaquila.tern.data.JsonStore
import io.github.luftaquila.tern.ui.theme.MonoFamily
import kotlinx.serialization.builtins.ListSerializer

/** Keeps the edited forwards across configuration changes. */
internal val ForwardsSaver = Saver<List<ForwardRule>, String>(
    save = { JsonStore.JSON.encodeToString(ListSerializer(ForwardRule.serializer()), it) },
    restore = { JsonStore.JSON.decodeFromString(ListSerializer(ForwardRule.serializer()), it) },
)

/** Lowest port an app may listen on; lower ones need root. */
private const val FIRST_UNPRIVILEGED_PORT = 1024

/** Picks the host to connect through. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JumpHostSection(selected: Host?, candidates: List<Host>, onSelect: (Host?) -> Unit) {
    FormSection(stringResource(R.string.section_jump)) {
        var expanded by remember { mutableStateOf(false) }
        val direct = stringResource(R.string.jump_none)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected?.displayName ?: direct,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.field_jump)) },
                supportingText = { Text(stringResource(R.string.jump_hint)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(direct) }, onClick = { onSelect(null); expanded = false })
                candidates.forEach { host ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(host.displayName)
                                Text(
                                    host.address,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = MonoFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { onSelect(host); expanded = false },
                    )
                }
            }
        }
    }
}

/** The host's port forwards; tapping one edits it. */
@Composable
internal fun ForwardsSection(forwards: List<ForwardRule>, onChange: (List<ForwardRule>) -> Unit) {
    // Index being edited; forwards.size means a new one.
    var editing by remember { mutableStateOf<Int?>(null) }
    FormSection(stringResource(R.string.section_forwarding)) {
        forwards.forEachIndexed { i, rule ->
            ForwardRow(rule) { editing = i }
        }
        OutlinedButton(onClick = { editing = forwards.size }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.forward_add))
        }
    }
    editing?.let { index ->
        ForwardDialog(
            initial = forwards.getOrNull(index),
            onSave = { rule ->
                onChange(if (index < forwards.size) forwards.toMutableList().also { it[index] = rule } else forwards + rule)
                editing = null
            },
            onDelete = { onChange(forwards.filterIndexed { i, _ -> i != index }); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ForwardRow(rule: ForwardRule, onClick: () -> Unit) {
    val destination = "${rule.destHost}:${rule.destPort}"
    val (icon, title, description) = when (rule.type) {
        ForwardType.LOCAL -> Triple(
            Icons.Rounded.PhoneAndroid,
            stringResource(R.string.forward_item_device, rule.port),
            stringResource(R.string.forward_item_local_desc, destination),
        )
        ForwardType.REMOTE -> Triple(
            Icons.Rounded.Dns,
            stringResource(R.string.forward_item_server, rule.port),
            stringResource(R.string.forward_item_remote_desc, destination),
        )
        ForwardType.DYNAMIC -> Triple(
            Icons.Rounded.Public,
            stringResource(R.string.forward_item_device, rule.port),
            stringResource(R.string.forward_item_dynamic_desc),
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ForwardDialog(initial: ForwardRule?, onSave: (ForwardRule) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(initial?.type ?: ForwardType.LOCAL) }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var destHost by remember { mutableStateOf(initial?.destHost ?: "localhost") }
    var destPort by remember { mutableStateOf(initial?.destPort?.takeIf { it > 0 }?.toString() ?: "") }
    var showErrors by remember { mutableStateOf(false) }

    val minPort = if (type == ForwardType.REMOTE) 1 else FIRST_UNPRIVILEGED_PORT
    val portValue = port.toIntOrNull()
    val portError = portValue == null || portValue !in minPort..65535
    val needsDestination = type != ForwardType.DYNAMIC
    val destHostError = needsDestination && destHost.isBlank()
    val destPortError = needsDestination && destPort.toIntOrNull().let { it == null || it !in 1..65535 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.forward_new else R.string.forward_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val types = listOf(
                        ForwardType.LOCAL to R.string.forward_type_local,
                        ForwardType.REMOTE to R.string.forward_type_remote,
                        ForwardType.DYNAMIC to R.string.forward_type_dynamic,
                    )
                    types.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = type == value,
                            onClick = { type = value },
                            shape = SegmentedButtonDefaults.itemShape(i, types.size),
                        ) { Text(stringResource(label)) }
                    }
                }
                Text(
                    stringResource(
                        when (type) {
                            ForwardType.LOCAL -> R.string.forward_type_local_desc
                            ForwardType.REMOTE -> R.string.forward_type_remote_desc
                            ForwardType.DYNAMIC -> R.string.forward_type_dynamic_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(if (type == ForwardType.REMOTE) R.string.forward_port_server else R.string.forward_port_device)) },
                    singleLine = true,
                    isError = showErrors && portError,
                    supportingText = if (showErrors && portError && portValue != null && portValue in 1 until minPort) {
                        { Text(stringResource(R.string.forward_port_min)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needsDestination) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = destHost,
                            onValueChange = { destHost = it.trim() },
                            label = { Text(stringResource(R.string.forward_dest_host)) },
                            singleLine = true,
                            isError = showErrors && destHostError,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = destPort,
                            onValueChange = { v -> destPort = v.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.forward_dest_port)) },
                            singleLine = true,
                            isError = showErrors && destPortError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (portError || destHostError || destPortError) {
                    showErrors = true
                } else {
                    onSave(
                        ForwardRule(
                            type = type,
                            port = portValue!!,
                            destHost = if (needsDestination) destHost else "",
                            destPort = if (needsDestination) destPort.toInt() else 0,
                        ),
                    )
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Agent forwarding switch, with what it allows and when to use it. */
@Composable
internal fun AgentSection(enabled: Boolean, onChange: (Boolean) -> Unit) {
    FormSection(stringResource(R.string.section_agent)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.agent_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}
