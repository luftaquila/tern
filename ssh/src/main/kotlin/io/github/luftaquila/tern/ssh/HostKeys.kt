// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import com.hierynomus.sshj.userauth.certificate.Certificate
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey
import java.util.Base64

/**
 * Runs suspend UI callbacks from sshj's own (non-coroutine) threads, e.g. the transport
 * reader thread during key exchange. The callback becomes a child of the connecting
 * coroutine's [parent] job, so cancelling the connection also cancels a pending prompt.
 */
internal class CallbackBridge(private val parent: Job) {
    fun <T> call(block: suspend () -> T): T = runBlocking(parent) { block() }
}

internal object HostKeys {
    fun info(host: String, port: Int, key: PublicKey): HostKeyInfo {
        val blob = KeyFiles.publicKeyBlob(key)
        return HostKeyInfo(
            host = host,
            port = port,
            keyType = KeyType.fromKey(key).toString(),
            publicKeyBase64 = Base64.getEncoder().encodeToString(blob),
            fingerprintSha256 = Fingerprints.sha256(blob),
        )
    }
}

/**
 * Verifies server host keys against a [KnownHostsStore], asking the user through
 * [SshInteraction.confirmHostKey] for unknown or changed keys and saving accepted ones. A host
 * certificate signed by one of [authorities] needs no confirmation and is not saved; its
 * signature, validity and principals are checked during the key exchange
 * (`Config.isVerifyHostKeyCertificates`).
 *
 * [verify] runs on sshj's transport reader thread in the middle of the key exchange, so the
 * connector raises the transport timeout while connecting (see [Connector]).
 */
internal class StoreHostKeyVerifier(
    private val host: String,
    private val port: Int,
    private val store: KnownHostsStore,
    private val interaction: SshInteraction,
    private val bridge: CallbackBridge,
    /** Public key blobs of the CAs trusted for this host ([trustedAuthorities]). */
    private val authorities: List<ByteArray> = emptyList(),
) : HostKeyVerifier {

    /** True once the user (or a failed prompt) rejected a key. */
    @Volatile
    var rejected: Boolean = false
        private set

    /** The last key that passed verification. */
    @Volatile
    var verified: HostKeyInfo? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        if (key !is Certificate<*>) return verifyKey(key)
        if (key.type == Certified.HOST_CERTIFICATE && authorities.any { it.contentEquals(key.signatureKey) }) {
            verified = HostKeys.info(host, this.port, key.key)
            return true
        }
        // Signed by a CA not trusted for this host: judged like the plain key it certifies.
        return verifyKey(key.key)
    }

    private fun verifyKey(key: PublicKey): Boolean {
        val info = HostKeys.info(host, this.port, key)
        val known = store.find(host, this.port)
        if (known.any { it.keyType == info.keyType && it.publicKeyBase64 == info.publicKeyBase64 }) {
            verified = info
            return true
        }
        val status = if (known.any { it.keyType == info.keyType }) HostKeyStatus.CHANGED else HostKeyStatus.UNKNOWN
        val accepted = bridge.call { interaction.confirmHostKey(info, status, known) }
        if (!accepted) {
            rejected = true
            return false
        }
        store.save(
            KnownHost(
                host = host,
                port = this.port,
                keyType = info.keyType,
                publicKeyBase64 = info.publicKeyBase64,
                addedAtMillis = System.currentTimeMillis(),
            ),
        )
        verified = info
        return true
    }

    /**
     * Key types already trusted for this host, so the KEX negotiates a key we know; with a trusted
     * CA, certificate types first (the config lists them first too, see [TernSshConfig.preferCertificateHostKeys]).
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val known = store.find(host, this.port).map { it.keyType }.distinct()
        return if (authorities.isEmpty()) known else Certified.KEY_TYPES.map { it.toString() } + known
    }

    companion object {
        /** Public key blobs of the CAs in [store] trusted for [host] on [port]; unreadable keys are left out. */
        fun trustedAuthorities(store: KnownHostsStore, host: String, port: Int): List<ByteArray> =
            store.certificateAuthorities()
                .filter { HostPatterns.matches(it.hostPatterns, host, port) }
                .mapNotNull { runCatching { Certified.blob(it.publicKey) }.getOrNull() }
    }
}
