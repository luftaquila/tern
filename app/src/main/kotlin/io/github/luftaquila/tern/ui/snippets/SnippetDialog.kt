// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.snippets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.KeyTextEscapes
import io.github.luftaquila.tern.ui.theme.MonoFamily

/** Most lines the text field grows to before it scrolls. */
private const val TEXT_MAX_LINES = 5

/** Line breaks typed or pasted into the text field, written as the `\n` escape (Enter). */
private fun String.escapeLineBreaks(): String = replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n")

/**
 * Edits a snippet or text key: an optional name and the text to send, written with
 * [KeyTextEscapes] escapes. Enter in the text field adds `\n`. [onConfirm] gets the trimmed name
 * and the parsed text; [onDelete] adds a delete button.
 */
@Composable
fun SnippetDialog(
    title: String,
    initialName: String,
    initialText: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, text: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var text by remember {
        val formatted = KeyTextEscapes.format(initialText)
        mutableStateOf(TextFieldValue(formatted, TextRange(formatted.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ek_custom_label)) },
                    placeholder = { Text("gs") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = if (v.text.none { it == '\n' || it == '\r' }) {
                            v
                        } else {
                            val cursor = v.selection.max
                            val head = v.text.substring(0, cursor).escapeLineBreaks()
                            TextFieldValue(head + v.text.substring(cursor).escapeLineBreaks(), TextRange(head.length))
                        }
                    },
                    label = { Text(stringResource(R.string.ek_custom_text)) },
                    placeholder = { Text("git status\\n", fontFamily = MonoFamily) },
                    supportingText = { Text(stringResource(R.string.ek_custom_hint)) },
                    maxLines = TEXT_MAX_LINES,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.text.isNotEmpty(), onClick = { onConfirm(name.trim(), KeyTextEscapes.parse(text.text)) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
