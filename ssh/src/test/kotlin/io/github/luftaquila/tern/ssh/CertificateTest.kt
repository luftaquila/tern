// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.agent.local.ProxyAgentFactory
import org.apache.sshd.certificate.OpenSshCertificateBuilder
import org.apache.sshd.common.PropertyResolverUtils
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.OpenSshCertificate
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.keyprovider.HostKeyCertificateProvider
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.shell.ShellFactory
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyPair
import java.security.PublicKey
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CertificateTest {

    private val userCa = ecKeyPair()
    private val hostCa = ecKeyPair()

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    private fun line(key: PublicKey): String = PublicKeyEntry.toString(key)

    private fun fingerprint(key: PublicKey): String = Fingerprints.sha256(KeyFiles.publicKeyBlob(key))

    private fun userCertificate(key: PublicKey, principals: List<String> = listOf(USER)): String =
        line(OpenSshCertificateBuilder.userCertificate().publicKey(key).id("$USER@test").principals(principals).sign(userCa))

    private fun hostCertificate(key: PublicKey, validBefore: Instant? = null): OpenSshCertificate =
        OpenSshCertificateBuilder.hostCertificate().publicKey(key).id("test-host").principals(listOf(HOST))
            .apply { if (validBefore != null) validBefore(validBefore) }
            .sign(hostCa)

    /** A server accepting only certificates of [userCa] for [USER], without SHA-1 RSA signatures (as OpenSSH 8.8 and later). */
    private fun certificateServer(offered: MutableList<PublicKey>) = TestServer {
        signatureFactories = listOf(
            BuiltinSignatures.nistp256, BuiltinSignatures.nistp256_cert,
            BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256,
            BuiltinSignatures.rsaSHA512_cert, BuiltinSignatures.rsaSHA256_cert,
        )
        publickeyAuthenticator = PublickeyAuthenticator { u, k, _ ->
            offered.add(k)
            u == USER && k is OpenSshCertificate && KeyUtils.compareKeys(k.caPubKey, userCa.public) && USER in k.principals
        }
        userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
    }

    private fun hostCertificateServer(hostKey: KeyPair, certificate: OpenSshCertificate) = TestServer(hostKey) {
        hostKeyCertificateProvider = HostKeyCertificateProvider { listOf(certificate) }
        passwordAuthenticator = PasswordAuthenticator { _, p, _ -> p == PASSWORD }
        userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
    }

    @Test
    fun userCertificateSignsInWhereOnlyCertificatesAreAccepted() {
        for (pair in listOf(ecKeyPair(), rsaKeyPair())) {
            val keyText = OpenSshKeyWriter.write(pair, "test", null)
            val offered = CopyOnWriteArrayList<PublicKey>()
            certificateServer(offered).use { server ->
                val certified = SshCredential.PrivateKey("k", keyText, certificate = userCertificate(pair.public))
                blocking { SshConnection.connect(server.target, listOf(certified), InMemoryKnownHosts(), ScriptedInteraction()) }.use {
                    assertTrue(it.isConnected, pair.public.algorithm)
                }
                assertTrue(offered.first() is OpenSshCertificate, "the certificate is offered before the plain key")

                assertFailsWith<SshAuthenticationException>("the plain key alone is not accepted") {
                    blocking { SshConnection.connect(server.target, listOf(SshCredential.PrivateKey("k", keyText)), InMemoryKnownHosts(), ScriptedInteraction()) }
                }
            }
        }
    }

    @Test
    fun certificateOfAnotherKeyIsNeverSent() {
        val pair = ecKeyPair()
        val offered = CopyOnWriteArrayList<PublicKey>()
        certificateServer(offered).use { server ->
            val wrong = SshCredential.PrivateKey("k", OpenSshKeyWriter.write(pair, "", null), certificate = userCertificate(ecKeyPair().public))
            assertFailsWith<SshAuthenticationException> {
                blocking { SshConnection.connect(server.target, listOf(wrong), InMemoryKnownHosts(), ScriptedInteraction()) }
            }
            assertTrue(offered.isNotEmpty() && offered.none { it is OpenSshCertificate }, "only the plain key was offered")
        }
    }

    @Test
    fun hostCertificateOfATrustedCaNeedsNoConfirmation() {
        val hostKey = ecKeyPair()
        hostCertificateServer(hostKey, hostCertificate(hostKey.public)).use { server ->
            for (postQuantum in listOf(true, false)) {
                val knownHosts = InMemoryKnownHosts(listOf(HostCertificateAuthority(line(hostCa.public), "127.0.0.*")))
                val ui = ScriptedInteraction(acceptHostKey = false)
                blocking {
                    SshConnection.connect(server.target, listOf(SshCredential.Password(PASSWORD)), knownHosts, ui, ConnectOptions(postQuantumKex = postQuantum))
                }.use { connection ->
                    assertEquals(fingerprint(hostKey.public), connection.hostKey.fingerprintSha256, "the certified key is the host key")
                }
                assertTrue(ui.hostKeyPrompts.isEmpty())
                assertTrue(knownHosts.entries.isEmpty(), "a certified host key is not saved")
            }
        }
    }

    @Test
    fun hostCertificateOfAnotherCaIsJudgedByItsKey() {
        val hostKey = ecKeyPair()
        hostCertificateServer(hostKey, hostCertificate(hostKey.public)).use { server ->
            // A CA trusted for this host, but not the one that signed its certificate.
            val knownHosts = InMemoryKnownHosts(listOf(HostCertificateAuthority(line(ecKeyPair().public), "*")))
            val ui = ScriptedInteraction()
            blocking { SshConnection.connect(server.target, listOf(SshCredential.Password(PASSWORD)), knownHosts, ui) }.close()
            val (info, status, _) = ui.hostKeyPrompts.single()
            assertEquals(HostKeyStatus.UNKNOWN, status)
            assertEquals(fingerprint(hostKey.public), info.fingerprintSha256)
            assertEquals(listOf("ecdsa-sha2-nistp256"), knownHosts.entries.map { it.keyType }, "the plain key is remembered")
        }
    }

    @Test
    fun expiredHostCertificateIsRefused() {
        val hostKey = ecKeyPair()
        hostCertificateServer(hostKey, hostCertificate(hostKey.public, validBefore = Instant.now().minusSeconds(60))).use { server ->
            for (postQuantum in listOf(true, false)) {
                val knownHosts = InMemoryKnownHosts(listOf(HostCertificateAuthority(line(hostCa.public), HOST)))
                val ui = ScriptedInteraction()
                val error = assertFailsWith<SshException> {
                    blocking {
                        SshConnection.connect(server.target, listOf(SshCredential.Password(PASSWORD)), knownHosts, ui, ConnectOptions(postQuantumKex = postQuantum))
                    }
                }
                assertTrue("certificate" in error.message.orEmpty().lowercase(), error.message)
                assertTrue(ui.hostKeyPrompts.isEmpty(), "never offered to the user instead")
            }
        }
    }

    @Test
    fun hostPatterns() {
        assertTrue(HostPatterns.matches("*.example.com", "a.Example.com", 22))
        assertTrue(HostPatterns.matches("*.example.com", "a.example.com", 2222), "any port")
        assertFalse(HostPatterns.matches("*.example.com", "example.com", 22))
        assertTrue(HostPatterns.matches("db?.lan, *.example.com", "db1.lan", 22))
        assertFalse(HostPatterns.matches("!bad.example.com,*.example.com", "bad.example.com", 22))
        assertTrue(HostPatterns.matches("[*.example.com]:2222", "a.example.com", 2222))
        assertFalse(HostPatterns.matches("[*.example.com]:2222", "a.example.com", 22))
        assertFalse(HostPatterns.matches("", "a.example.com", 22))
        assertFalse(HostPatterns.matches("!*.lan", "a.lan", 22), "exclusions alone cover nothing")
    }

    @Test
    fun certificateInfo() {
        val pair = ecKeyPair()
        val info = SshCertificates.inspect(userCertificate(pair.public, principals = listOf(USER, "deploy")))
        assertEquals("ecdsa-sha2-nistp256-cert-v01@openssh.com", info.certificateType)
        assertFalse(info.isHostCertificate)
        assertEquals("$USER@test", info.id)
        assertEquals(listOf(USER, "deploy"), info.principals)
        assertNull(info.validAfterMillis)
        assertNull(info.validBeforeMillis, "valid forever")
        assertEquals(fingerprint(pair.public), info.keyFingerprintSha256)
        assertEquals(fingerprint(userCa.public), info.caFingerprintSha256)

        val until = Instant.now().plusSeconds(3600)
        val host = SshCertificates.inspect(line(hostCertificate(pair.public, validBefore = until)))
        assertTrue(host.isHostCertificate)
        assertEquals(until.epochSecond * 1000, host.validBeforeMillis)
        assertTrue(host.isValidAt(System.currentTimeMillis()))
        assertFalse(host.isValidAt(until.toEpochMilli() + 1000))

        assertFailsWith<SshException> { SshCertificates.inspect(line(pair.public)) }
        assertFailsWith<SshException> { SshCertificates.inspect("ssh-ed25519-cert-v01@openssh.com bm90IGEga2V5") }
        assertEquals(fingerprint(pair.public), SshCertificates.publicKeyFingerprint(line(pair.public)))
        assertFailsWith<SshException> { SshCertificates.publicKeyFingerprint(userCertificate(pair.public)) }
    }

    @Test
    fun agentOffersTheCertificateFirst() {
        val key = SshKeys.generate(SshKeyType.ED25519, "")
        val public = PublicKeyEntry.parsePublicKeyEntry(key.publicKey).resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.FAILING)
        val certificate = userCertificate(public)
        val agent = SshAgent(
            listOf(AgentKey(key.publicKey, "phone", certificate) { SshCredential.PrivateKey("phone", key.privateKey) }),
            ScriptedInteraction(),
        )
        val shells = CopyOnWriteArrayList<RecordingShell>()
        TestServer {
            passwordAuthenticator = PasswordAuthenticator { _, p, _ -> p == PASSWORD }
            userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            agentFactory = ProxyAgentFactory()
            shellFactory = ShellFactory { RecordingShell().also(shells::add) }
        }.use { server ->
            val connection = blocking {
                SshConnection.connect(server.target, listOf(SshCredential.Password(PASSWORD)), InMemoryKnownHosts(), ScriptedInteraction())
            }
            connection.use {
                connection.openShell("xterm-256color", 80, 24, 0, 0, emptyMap(), agent)
                val shell = shells.single()
                assertTrue(shell.started.await(10, TimeUnit.SECONDS))
                PropertyResolverUtils.updateProperty(server.server, "SSH_AUTH_SOCK", assertNotNull(shell.env["SSH_AUTH_SOCK"]))
                val session = server.server.activeSessions.single()
                server.server.agentFactory.createClient(session, server.server).use { client ->
                    val identities = client.identities.toList()
                    assertTrue(identities[0].key is OpenSshCertificate, "the certificate comes first")
                    assertEquals(listOf("phone", "phone"), identities.map { it.value })

                    val data = "session data to sign".toByteArray()
                    val (algorithm, signature) = client.sign(session, identities[0].key, "ssh-ed25519-cert-v01@openssh.com", data)
                    assertEquals("ssh-ed25519", algorithm)
                    val blob = Base64.getDecoder().decode(key.publicKey.split(' ')[1])
                    val verifier = Ed25519Signer().apply {
                        init(false, Ed25519PublicKeyParameters(blob, blob.size - 32))
                        update(data, 0, data.size)
                    }
                    assertTrue(verifier.verifySignature(signature), "signed with the certified key")
                }
            }
        }
    }
}
