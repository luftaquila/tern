// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import io.github.luftaquila.tern.ssh.KnownHost
import io.github.luftaquila.tern.ssh.KnownHostsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class HostRepository(dir: File, scope: CoroutineScope) {
    private val store = JsonStore(File(dir, "hosts.json"), ListSerializer(Host.serializer()), emptyList(), scope)
    val hosts: StateFlow<List<Host>> = store.state

    fun get(id: String): Host? = store.value.firstOrNull { it.id == id }

    fun upsert(host: Host) = store.update { list ->
        if (list.any { it.id == host.id }) list.map { if (it.id == host.id) host else it } else list + host
    }

    /** Replaces the hosts with the same ids, adds the others. */
    fun upsertAll(hosts: List<Host>) = store.update { list ->
        val byId = hosts.associateBy { it.id }
        list.map { byId[it.id] ?: it } + hosts.filter { new -> list.none { it.id == new.id } }
    }

    /** Hosts that went through the deleted one connect directly afterwards. */
    fun delete(id: String) = store.update { list ->
        list.filterNot { it.id == id }.map { if (it.jumpHostId == id) it.copy(jumpHostId = null) else it }
    }

    fun markUsed(id: String) = store.update { list ->
        list.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
    }

    /** Rewrites every stored password with [transform] and waits until it is saved. */
    fun reencrypt(transform: (String) -> String) {
        store.update { list -> list.map { it.copy(encryptedPassword = it.encryptedPassword?.let(transform)) } }
        store.flush()
    }

    /** Called when a key is deleted so hosts don't point at a missing key. */
    fun detachKey(keyId: String) = store.update { list ->
        list.map { if (it.keyId == keyId) it.copy(keyId = null) else it }
    }
}

class KeyRepository(dir: File, scope: CoroutineScope) {
    private val store = JsonStore(File(dir, "keys.json"), ListSerializer(SshKeyEntry.serializer()), emptyList(), scope)
    val keys: StateFlow<List<SshKeyEntry>> = store.state

    fun get(id: String): SshKeyEntry? = store.value.firstOrNull { it.id == id }

    fun add(key: SshKeyEntry) = store.update { it + key }

    /** Adds [keys] and waits until they are saved. */
    fun addAll(keys: List<SshKeyEntry>) {
        if (keys.isEmpty()) return
        store.update { it + keys }
        store.flush()
    }

    fun rename(id: String, name: String) = store.update { list ->
        list.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun delete(id: String) = store.update { list -> list.filterNot { it.id == id } }

    /** Rewrites every private key and passphrase with [transform] and waits until they are saved. */
    fun reencrypt(transform: (String) -> String) {
        store.update { list ->
            list.map { it.copy(encryptedPrivateKey = transform(it.encryptedPrivateKey), encryptedPassphrase = it.encryptedPassphrase?.let(transform)) }
        }
        store.flush()
    }
}

class KnownHostRepository(dir: File, scope: CoroutineScope) : KnownHostsStore {
    private val store = JsonStore(
        File(dir, "known_hosts.json"), ListSerializer(KnownHostEntry.serializer()), emptyList(), scope,
    )
    val entries: StateFlow<List<KnownHostEntry>> = store.state

    override fun find(host: String, port: Int): List<KnownHost> =
        store.value.filter { it.host.equals(host, ignoreCase = true) && it.port == port }.map {
            KnownHost(it.host, it.port, it.keyType, it.publicKeyBase64, it.addedAtMillis)
        }

    override fun save(entry: KnownHost) = store.update { list ->
        list.filterNot {
            it.host.equals(entry.host, ignoreCase = true) && it.port == entry.port && it.keyType == entry.keyType
        } + KnownHostEntry(entry.host, entry.port, entry.keyType, entry.publicKeyBase64, entry.addedAtMillis)
    }

    fun delete(entry: KnownHostEntry) = store.update { list -> list.filterNot { it == entry } }
}

class SnippetRepository(dir: File, scope: CoroutineScope) {
    private val store = JsonStore(File(dir, "snippets.json"), ListSerializer(Snippet.serializer()), emptyList(), scope)
    val snippets: StateFlow<List<Snippet>> = store.state
    val value: List<Snippet> get() = store.value

    fun get(id: String): Snippet? = store.value.firstOrNull { it.id == id }

    /** Replaces the snippets with the same ids, adds the others. */
    fun upsert(vararg snippets: Snippet) = upsertAll(snippets.asList())

    fun upsertAll(snippets: List<Snippet>) = store.update { list ->
        val byId = snippets.associateBy { it.id }
        list.map { byId[it.id] ?: it } + snippets.filter { new -> list.none { it.id == new.id } }
    }

    /** Deletes the snippet and its keys on the extra-keys bar. */
    fun delete(id: String, settings: SettingsRepository) {
        store.update { list -> list.filterNot { it.id == id } }
        settings.update { it.copy(extraKeys = it.extraKeys.withoutSnippet(id)) }
    }

    /** Turns macro keys of the extra-keys layout into snippet keys ([ExtraKeysConfig.macrosAsSnippets]). */
    fun adoptMacroKeys(settings: SettingsRepository) {
        val (config, added) = settings.value.extraKeys.macrosAsSnippets(store.value)
        if (config == settings.value.extraKeys) return
        store.update { it + added }
        store.flush() // the snippets are saved before any key points at them
        settings.update { it.copy(extraKeys = config) }
    }
}

class SettingsRepository(dir: File, scope: CoroutineScope) {
    private val store = JsonStore(
        File(dir, "settings.json"),
        AppSettings.serializer(),
        AppSettings(settingsVersion = AppSettings.SETTINGS_VERSION),
        scope,
    )
    val settings: StateFlow<AppSettings> = store.state

    init {
        if (store.value.settingsVersion < AppSettings.SETTINGS_VERSION) store.update { it.migrated() }
    }
    val value: AppSettings get() = store.value

    fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)

    /** Rewrites the app lock verifier with [transform] and waits until it is saved. */
    fun reencrypt(transform: (String) -> String) {
        store.update { it.copy(lockSecret = it.lockSecret?.let(transform)) }
        store.flush()
    }
}
