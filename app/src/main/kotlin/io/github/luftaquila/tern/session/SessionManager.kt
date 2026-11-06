// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.session

import io.github.luftaquila.tern.AppContainer
import io.github.luftaquila.tern.data.Host
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** All open terminal sessions of the process. Keeps the foreground service running while any exist. */
class SessionManager(private val container: AppContainer) {
    private val nextId = AtomicLong(1)
    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    fun open(host: Host): TerminalSession {
        val session = TerminalSession(nextId.getAndIncrement(), host, container)
        _sessions.update { it + session }
        SessionService.start(container.context)
        session.connect()
        return session
    }

    fun find(id: Long): TerminalSession? = _sessions.value.firstOrNull { it.id == id }

    fun close(id: Long) {
        val session = find(id) ?: return
        session.close()
        _sessions.update { list -> list.filterNot { it.id == id } }
    }

    fun closeAll() {
        val all = _sessions.value
        _sessions.value = emptyList()
        all.forEach { it.close() }
    }

    fun applyColorScheme(id: String) {
        _sessions.value.forEach { it.applyColorScheme(id) }
    }
}
