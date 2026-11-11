// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern

import android.app.Application
import android.content.Context
import io.github.luftaquila.tern.data.HostRepository
import io.github.luftaquila.tern.data.KeyRepository
import io.github.luftaquila.tern.data.KnownHostRepository
import io.github.luftaquila.tern.data.SecretBox
import io.github.luftaquila.tern.data.SettingsRepository
import io.github.luftaquila.tern.data.SnippetRepository
import io.github.luftaquila.tern.session.SessionManager
import io.github.luftaquila.tern.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TernApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        SshSecurity.install()
        container = AppContainer(this)
        container.purchases.start()
    }
}

/** Process-wide singletons. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hosts = HostRepository(context.filesDir, scope)
    val keys = KeyRepository(context.filesDir, scope)
    val knownHosts = KnownHostRepository(context.filesDir, scope)
    val hostCas = io.github.luftaquila.tern.data.HostCaRepository(context.filesDir, scope)
    val settings = SettingsRepository(context.filesDir, scope)
    val snippets = SnippetRepository(context.filesDir, scope)
    val backups = io.github.luftaquila.tern.data.Backups(hosts, keys, snippets)
    val sessions = SessionManager(this)
    val lock = io.github.luftaquila.tern.ui.lock.AppLock(settings)
    val purchases = io.github.luftaquila.tern.billing.Purchases(context)

    init {
        // Secrets saved under an earlier Keystore alias move to the current key before anything reads them.
        SecretBox.migrateEarlierKeys { transform ->
            hosts.reencrypt(transform)
            keys.reencrypt(transform)
            settings.reencrypt(transform)
        }
        snippets.adoptMacroKeys(settings)
    }
}
