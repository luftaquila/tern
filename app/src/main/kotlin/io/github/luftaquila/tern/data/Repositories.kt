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
