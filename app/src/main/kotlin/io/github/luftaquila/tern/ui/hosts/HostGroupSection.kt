// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.Host
import io.github.luftaquila.tern.data.JsonStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Keeps the edited tags across configuration changes. */
internal val TagsSaver = Saver<List<String>, String>(
    save = { JsonStore.JSON.encodeToString(ListSerializer(String.serializer()), it) },
    restore = { JsonStore.JSON.decodeFromString(ListSerializer(String.serializer()), it) },
)

/** Most suggestions shown under the tag field. */
private const val MAX_TAG_SUGGESTIONS = 8

/**
 * [tags] with the tag typed as [raw] added, spelled like an existing one in [known] that differs
 * only in case. Unchanged when [raw] is blank or the tag is there already.
 */
internal fun withTag(tags: List<String>, raw: String, known: List<String>): List<String> {
    val typed = Host.tagOf(raw)
    if (typed.isEmpty() || tags.any { it.equals(typed, ignoreCase = true) }) return tags
    return tags + (known.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed)
}

/** [tags] with each comma-separated tag of [typed] added as by [withTag]. */
internal fun withTags(tags: List<String>, typed: String, known: List<String>): List<String> =
    typed.split(',').fold(tags) { list, raw -> withTag(list, raw, known) }

/** The host's group, typed or picked from [knownGroups], and its tags. */
@Composable
internal fun GroupSection(
    group: String,
    onGroupChange: (String) -> Unit,
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    tagInput: String,
    onTagInputChange: (String) -> Unit,
    knownGroups: List<String>,
    knownTags: List<String>,
) {
    FormSection(stringResource(R.string.section_organize)) {
        GroupField(group, knownGroups, onGroupChange)
        TagsField(tags, onTagsChange, tagInput, onTagInputChange, knownTags)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupField(value: String, known: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val typed = value.trim()
    val matches = known.filter { typed.isEmpty() || it.contains(typed, ignoreCase = true) }
    ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onChange(it)
                expanded = true
            },
            label = { Text(stringResource(R.string.field_group)) },
            placeholder = { Text(stringResource(R.string.field_group_none)) },
            singleLine = true,
            trailingIcon = if (known.isEmpty()) null else ({ ExposedDropdownMenuDefaults.TrailingIcon(expanded && matches.isNotEmpty()) }),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onChange(name)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * The tags as removable chips, a field adding more (several at once separated by commas), and
 * tags of other hosts to pick. The typed text is split only when added, never while typing, so
 * the keyboard's composition is left alone.
 */
@Composable
private fun TagsField(
    tags: List<String>,
    onChange: (List<String>) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    known: List<String>,
) {
    fun addTyped() {
        onChange(withTags(tags, input, known))
        onInputChange("")
    }
    if (tags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { onChange(tags - tag) },
                    label = { Text("#$tag") },
                    trailingIcon = {
                        Icon(Icons.Rounded.Close, stringResource(R.string.tag_remove, tag), Modifier.size(InputChipDefaults.IconSize))
                    },
                )
            }
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = onInputChange,
        label = { Text(stringResource(R.string.field_tag_add)) },
        supportingText = { Text(stringResource(R.string.tag_hint)) },
        singleLine = true,
        trailingIcon = if (withTags(emptyList(), input, known).isEmpty()) null else ({
            IconButton(onClick = ::addTyped) { Icon(Icons.Rounded.Add, stringResource(R.string.field_tag_add)) }
        }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { addTyped() }),
        modifier = Modifier.fillMaxWidth(),
    )
    // Suggestions for the tag being typed, after the last comma.
    val typed = Host.tagOf(input.substringAfterLast(','))
    val suggestions = known
        .filter { k -> tags.none { it.equals(k, ignoreCase = true) } && (typed.isEmpty() || k.contains(typed, ignoreCase = true)) }
        .take(MAX_TAG_SUGGESTIONS)
    if (suggestions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { tag ->
                SuggestionChip(
                    onClick = {
                        onChange(withTag(withTags(tags, input.substringBeforeLast(',', ""), known), tag, known))
                        onInputChange("")
                    },
                    label = { Text("#$tag") },
                )
            }
        }
    }
}
