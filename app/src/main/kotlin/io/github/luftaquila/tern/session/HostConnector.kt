// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.session

import io.github.luftaquila.tern.AppContainer
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AuthMethod
import io.github.luftaquila.tern.data.Host
import io.github.luftaquila.tern.data.HostRepository
import io.github.luftaquila.tern.data.SecretBox
import io.github.luftaquila.tern.ssh.AgentKey
import io.github.luftaquila.tern.ssh.ConnectOptions
import io.github.luftaquila.tern.ssh.SshAgent
import io.github.luftaquila.tern.ssh.SshConnection
import io.github.luftaquila.tern.ssh.SshCredential
import io.github.luftaquila.tern.ssh.SshTarget

/**
 * Connects to hosts the way they are set up: with their stored credentials and through their
 * jump hosts. Passwords the user asks to remember are saved to the host they belong to.
 */
class HostConnector(private val container: AppContainer, private val prompts: PromptBridge) {

    /**
     * Connects to [host], first to each of its jump hosts. [onHop] is told about every host before
     * connecting to it.
     */
    suspend fun connect(host: Host, onHop: (hop: Host, via: Host?) -> Unit = { _, _ -> }): SshConnection {
        val hops = jumpHosts(container.hosts, host) + host
        var via: SshConnection? = null
        try {
            hops.forEachIndexed { i, hop ->
                onHop(hop, hops.getOrNull(i - 1))
                prompts.passwordToSave = null
                via = SshConnection.connect(
                    target = SshTarget(hop.hostname, hop.port, hop.username),
                    credentials = credentials(hop),
                    knownHosts = container.knownHosts,
                    interaction = prompts,
                    options = ConnectOptions(
                        keepAliveIntervalSeconds = hop.keepAliveSeconds,
                        postQuantumKex = container.settings.value.postQuantumKex,
                    ),
                    via = via,
                )
                prompts.passwordToSave?.let { password ->
                    container.hosts.get(hop.id)?.let { container.hosts.upsert(it.copy(encryptedPassword = SecretBox.encrypt(password))) }
                    prompts.passwordToSave = null
                }
            }
        } catch (t: Throwable) {
            via?.close() // connected hops are closed by a failed next hop too; closing twice is harmless
            throw t
        }
        return via ?: throw IllegalStateException(container.context.getString(R.string.error_host_missing))
    }

    /** Every key of the app, offered to the server for agent forwarding. */
    fun agent(): SshAgent = SshAgent(
        container.keys.keys.value.map { key ->
            AgentKey(key.publicKey, key.name) {
                SshCredential.PrivateKey(key.name, SecretBox.decrypt(key.encryptedPrivateKey), SecretBox.decryptOrNull(key.encryptedPassphrase))
            }
        },
        prompts,
    )

    private fun credentials(host: Host): List<SshCredential> = buildList {
        if (host.authMethod == AuthMethod.KEY) {
            host.keyId?.let { container.keys.get(it) }?.let { key ->
                add(SshCredential.PrivateKey(key.name, SecretBox.decrypt(key.encryptedPrivateKey), SecretBox.decryptOrNull(key.encryptedPassphrase)))
            }
        }
        SecretBox.decryptOrNull(host.encryptedPassword)?.let { add(SshCredential.Password(it)) }
    }

    companion object {
        /**
         * The hosts to pass through on the way to [host], outermost first. The chain ends where it
         * would repeat a host (A through B through A) or at a jump host that no longer exists.
         */
        fun jumpHosts(hosts: HostRepository, host: Host): List<Host> {
            val chain = ArrayDeque<Host>()
            val seen = mutableSetOf(host.id)
            var next = host.jumpHostId
            while (next != null && seen.add(next)) {
                val jump = hosts.get(next) ?: break
                chain.addFirst(jump)
                next = jump.jumpHostId
            }
            return chain
        }
    }
}
