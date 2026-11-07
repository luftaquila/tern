// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.terminal

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.KeyTextEscapes
import io.github.luftaquila.tern.data.Snippet
import io.github.luftaquila.tern.ui.theme.MonoFamily

/** Most of the terminal's height the panel may cover; longer lists scroll. */
private const val MAX_HEIGHT_FRACTION = 0.6f

/**
 * The snippets to send, over the bottom of the terminal. Like the extra keys it takes taps as raw
 * pointer input, so the terminal keeps focus and the keyboard stays up.
 */
@Composable
internal fun SnippetPanel(
    snippets: List<Snippet>,
    onSend: (Snippet) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * MAX_HEIGHT_FRACTION).align(Alignment.BottomCenter),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.snippets_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Tappable(onTap = onManage, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            stringResource(R.string.snippets_manage),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    Tappable(onTap = onClose, shape = RoundedCornerShape(12.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            stringResource(R.string.action_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp).size(20.dp),
                        )
                    }
                }
                if (snippets.isEmpty()) {
                    Text(
                        stringResource(R.string.snippets_empty_panel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                    )
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                        snippets.forEach { snippet ->
                            Tappable(onTap = { onSend(snippet) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                                    Text(snippet.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (snippet.name.isNotBlank()) {
                                        Text(
                                            KeyTextEscapes.format(snippet.text),
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
                    }
                }
            }
        }
    }
}

/**
 * [content] firing [onTap] when the finger lifts, like an extra key: a touch that turns into a
 * scroll fires nothing, and focus never moves.
 */
@Composable
private fun Tappable(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(0.dp),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val currentOnTap by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            currentOnTap()
                        }
                    },
                )
            },
    ) {
        content()
    }
}
