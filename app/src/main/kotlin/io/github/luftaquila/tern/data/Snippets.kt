// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A saved command, sent like a text key: typed when plain, as-is when it has control characters
 * ([KeyTextEscapes.isRaw]). On the extra-keys bar through [ExtraKeyDef.SnippetRef].
 */
@Serializable
data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    /** Shown in lists and on its keys; empty = the text itself. */
    val name: String = "",
    val text: String = "",
) {
    val title: String get() = name.ifBlank { KeyTextEscapes.display(text) }
}
