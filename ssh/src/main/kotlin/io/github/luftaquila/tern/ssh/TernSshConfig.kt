// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import com.hierynomus.sshj.key.KeyAlgorithm
import com.hierynomus.sshj.key.KeyAlgorithms
import com.hierynomus.sshj.transport.cipher.BlockCiphers
import com.hierynomus.sshj.transport.cipher.ChachaPolyCiphers
import com.hierynomus.sshj.transport.cipher.GcmCiphers
import com.hierynomus.sshj.transport.kex.DHGroups
import com.hierynomus.sshj.transport.kex.ExtInfoClientFactory
import com.hierynomus.sshj.transport.kex.ExtendedDHGroups
import com.hierynomus.sshj.transport.mac.Macs
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureRSA
import net.schmizz.sshj.transport.cipher.Cipher
import net.schmizz.sshj.transport.kex.Curve25519SHA256
import net.schmizz.sshj.transport.kex.DHGexSHA1
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import java.util.LinkedList

/**
 * sshj configuration with a modern algorithm preference order. Legacy algorithms stay at
 * the end of each list so old servers (routers, NAS boxes) still connect; broken ones
 * (arcfour, MD5/RIPEMD MACs, truncated MACs, exotic block ciphers) are dropped.
 *
 * Certificate key algorithms come last: they serve user certificates, and as host keys only for
 * hosts a trusted CA covers ([preferCertificateHostKeys]). FIDO (sk-*) key algorithms are omitted.
 */
internal class TernSshConfig : DefaultConfig() {

    init {
        // Detects dead peers (unanswered keepalive@openssh.com) instead of only sending
        // SSH_MSG_IGNORE like the default HEARTBEAT provider.
        keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
    }

    override fun initKeyExchangeFactories() {
        setKeyExchangeFactories(
            Curve25519SHA256.FactoryLibSsh(), // curve25519-sha256
            Curve25519SHA256.Factory(), // curve25519-sha256@libssh.org
            ECDHNistP.Factory256(),
            ECDHNistP.Factory384(),
            ECDHNistP.Factory521(),
            DHGexSHA256.Factory(),
            DHGroups.Group16SHA512(),
            DHGroups.Group18SHA512(),
            DHGroups.Group14SHA256(),
            ExtendedDHGroups.Group16SHA512AtSSH(),
            ExtendedDHGroups.Group18SHA512AtSSH(),
            ExtendedDHGroups.Group14SHA256AtSSH(),
            // Legacy fallbacks.
            DHGroups.Group14SHA1(),
            DHGexSHA1.Factory(),
            DHGroups.Group1SHA1(),
            // RFC 8308 ext-info-c: lets the server announce server-sig-algs (rsa-sha2-*).
            ExtInfoClientFactory(),
        )
    }

    /** Puts the post-quantum hybrid key exchanges ([PostQuantumKex]) first. */
    fun preferPostQuantum() {
        setKeyExchangeFactories(PostQuantumKex.factories() + getKeyExchangeFactories())
    }

    override fun initKeyAlgorithms() {
        keyAlgorithms = listOf<Factory.Named<KeyAlgorithm>>(
            KeyAlgorithms.EdDSA25519(),
            KeyAlgorithms.ECDSASHANistp256(),
            KeyAlgorithms.ECDSASHANistp384(),
            KeyAlgorithms.ECDSASHANistp521(),
            KeyAlgorithms.RSASHA512(),
            KeyAlgorithms.RSASHA256(),
            KeyAlgorithms.SSHRSA(),
            KeyAlgorithms.SSHDSA(),
        ) + CERTIFICATE_KEY_ALGORITHMS
    }

    /** Puts the certificate host key algorithms first, for a host whose certificates a trusted CA signs. */
    fun preferCertificateHostKeys() {
        val (certificates, plain) = keyAlgorithms.partition { it in CERTIFICATE_KEY_ALGORITHMS }
        keyAlgorithms = certificates + plain
    }

    /**
     * RSA certificates with SHA-2 signatures (sshj only has the SHA-1 `ssh-rsa-cert-v01`, which
     * OpenSSH 8.8 and later refuse); the signature inside is the plain `rsa-sha2-*` one.
     */
    private class RsaCertificateSignature(private val jcaName: String, private val signatureName: String) : Factory.Named<Signature> {
        override fun getName(): String = signatureName

        override fun create(): Signature = SignatureRSA(jcaName, KeyType.RSA_CERT, signatureName)
    }

    private companion object {
        val CERTIFICATE_KEY_ALGORITHMS: List<Factory.Named<KeyAlgorithm>> = listOf(
            KeyAlgorithms.EdDSA25519CertV01(),
            KeyAlgorithms.ECDSASHANistp256CertV01(),
            KeyAlgorithms.ECDSASHANistp384CertV01(),
            KeyAlgorithms.ECDSASHANistp521CertV01(),
            KeyAlgorithms.Factory("rsa-sha2-512-cert-v01@openssh.com", RsaCertificateSignature("SHA512withRSA", "rsa-sha2-512"), KeyType.RSA_CERT),
            KeyAlgorithms.Factory("rsa-sha2-256-cert-v01@openssh.com", RsaCertificateSignature("SHA256withRSA", "rsa-sha2-256"), KeyType.RSA_CERT),
            KeyAlgorithms.SSHRSACertV01(),
        )
    }

    override fun getDefaultCipherFactories(): MutableList<Factory.Named<Cipher>> =
        LinkedList(
            listOf<Factory.Named<Cipher>>(
                ChachaPolyCiphers.CHACHA_POLY_OPENSSH(),
                GcmCiphers.AES256GCM(),
                GcmCiphers.AES128GCM(),
                BlockCiphers.AES256CTR(),
                BlockCiphers.AES192CTR(),
                BlockCiphers.AES128CTR(),
                BlockCiphers.AES256CBC(),
                BlockCiphers.AES192CBC(),
                BlockCiphers.AES128CBC(),
                BlockCiphers.TripleDESCBC(),
            ),
        )

    override fun initMACFactories() {
        setMACFactories(
            Macs.HMACSHA2256Etm(),
            Macs.HMACSHA2512Etm(),
            Macs.HMACSHA2256(),
            Macs.HMACSHA2512(),
            Macs.HMACSHA1Etm(),
            Macs.HMACSHA1(),
        )
    }
}
