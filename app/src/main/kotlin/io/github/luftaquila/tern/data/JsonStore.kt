// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A tiny persistent value: loaded synchronously on construction, kept in a [StateFlow], and
 * written atomically (temp file + rename) on a single background writer after every update.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: T,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(load() ?: default)
    val state: StateFlow<T> = _state.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    val value: T get() = _state.value

    fun update(transform: (T) -> T) {
        _state.update(transform)
        val snapshot = _state.value
        scope.launch(writer) { write(snapshot) }
    }

    /** Waits until every update made so far is written (the writer runs them in order). */
    fun flush() {
        runBlocking { withContext(writer) {} }
    }

    private fun load(): T? {
        if (!file.exists()) return null
        return try {
            JSON.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ${file.name}; keeping a backup and starting fresh", e)
            file.copyTo(File(file.path + ".corrupt"), overwrite = true)
            null
        }
    }

    private fun write(value: T) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSON.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) error("rename failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${file.name}", e)
        }
    }

    companion object {
        private const val TAG = "JsonStore"
        val JSON = Json {
            ignoreUnknownKeys = true
            // A value the app no longer has (a removed font, say) falls back to the field's default.
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}
