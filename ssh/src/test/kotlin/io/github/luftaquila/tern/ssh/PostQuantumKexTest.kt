// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.kex.KexProposalOption
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The hybrid post-quantum key exchanges against MINA SSHD restricted to one of them. (Checked by
 * hand against OpenSSH 10.3 as well; MINA uses BouncyCastle too, so this alone would not prove
 * interoperability.)
 */
class PostQuantumKexTest {

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    /** A password server offering only [kex]; records the algorithm each session negotiated. */
    private fun server(kex: BuiltinDHFactories, negotiated: MutableList<String>) = TestServer {
        passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == USER && p == PASSWORD }
        userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
        keyExchangeFactories = listOf(ServerBuilder.DH2KEX.apply(kex))
        addSessionListener(object : SessionListener {
            override fun sessionNegotiationEnd(
                session: Session,
                clientProposal: MutableMap<KexProposalOption, String>?,
                serverProposal: MutableMap<KexProposalOption, String>?,
                negotiatedOptions: MutableMap<KexProposalOption, String>?,
                reason: Throwable?,
            ) {
                negotiatedOptions?.get(KexProposalOption.ALGORITHMS)?.let { negotiated += it }
            }
        })
    }

    private fun connectAndRun(server: TestServer, options: ConnectOptions = ConnectOptions()) =
        blocking {
            SshConnection.connect(server.target, listOf(SshCredential.Password(PASSWORD)), InMemoryKnownHosts(), ScriptedInteraction(), options)
        }.use { c -> assertTrue(c.isConnected) }

    @Test
    fun mlkem768x25519() {
        val negotiated = CopyOnWriteArrayList<String>()
        server(BuiltinDHFactories.mlkem768x25519, negotiated).use { connectAndRun(it) }
        assertEquals(listOf("mlkem768x25519-sha256"), negotiated)
    }

    @Test
    fun sntrup761x25519() {
        val negotiated = CopyOnWriteArrayList<String>()
        server(BuiltinDHFactories.sntrup761x25519, negotiated).use { connectAndRun(it) }
        assertEquals(listOf("sntrup761x25519-sha512"), negotiated)
    }

    @Test
    fun sntrup761x25519OpensshName() {
        val negotiated = CopyOnWriteArrayList<String>()
        server(BuiltinDHFactories.sntrup761x25519_openssh, negotiated).use { connectAndRun(it) }
        assertEquals(listOf("sntrup761x25519-sha512@openssh.com"), negotiated)
    }

    @Test
    fun preferredWhenTheServerOffersClassicToo() {
        val negotiated = CopyOnWriteArrayList<String>()
        TestServer {
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == USER && p == PASSWORD }
            userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
            keyExchangeFactories = listOf(BuiltinDHFactories.curve25519, BuiltinDHFactories.mlkem768x25519).map { ServerBuilder.DH2KEX.apply(it) }
            addSessionListener(object : SessionListener {
                override fun sessionNegotiationEnd(
                    session: Session,
                    clientProposal: MutableMap<KexProposalOption, String>?,
                    serverProposal: MutableMap<KexProposalOption, String>?,
                    negotiatedOptions: MutableMap<KexProposalOption, String>?,
                    reason: Throwable?,
                ) {
                    negotiatedOptions?.get(KexProposalOption.ALGORITHMS)?.let { negotiated += it }
                }
            })
        }.use { connectAndRun(it) }
        assertEquals(listOf("mlkem768x25519-sha256"), negotiated, "the client lists the hybrid exchange first")
    }

    @Test
    fun disabledMeansNotOffered() {
        val negotiated = CopyOnWriteArrayList<String>()
        server(BuiltinDHFactories.mlkem768x25519, negotiated).use { server ->
            assertFailsWith<Exception> { connectAndRun(server, ConnectOptions(postQuantumKex = false)) }
        }
    }

    @Test
    fun rawSecretEncodesAsString() {
        // sshj writes K with putMPInt; a hybrid K must come out as a plain SSH string.
        val leadingHighBit = ByteArray(32) { (0x80 + it).toByte() }
        val leadingZero = ByteArray(32) { if (it == 0) 0 else it.toByte() }
        for (raw in listOf(leadingHighBit, leadingZero)) {
            val encoded = net.schmizz.sshj.common.Buffer.PlainBuffer().putMPInt(RawSecret(raw)).compactData
            val expected = net.schmizz.sshj.common.Buffer.PlainBuffer().putBytes(raw).compactData
            assertTrue(encoded.contentEquals(expected))
        }
    }
}
