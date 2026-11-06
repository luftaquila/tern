// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.agent.local.ProxyAgentFactory
import org.apache.sshd.common.PropertyResolverUtils
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.shell.ShellFactory
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentForwardingTest {

    @Test
    fun programsOnTheServerSignWithTheAppsKeys() {
        val ed25519 = SshKeys.generate(SshKeyType.ED25519, "")
        val rsa = SshKeys.generate(SshKeyType.RSA_3072, "")
        val agent = SshAgent(
            listOf(
                AgentKey(ed25519.publicKey, "phone") { SshCredential.PrivateKey("phone", ed25519.privateKey) },
                AgentKey(rsa.publicKey, "laptop") { SshCredential.PrivateKey("laptop", rsa.privateKey) },
            ),
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
            val connection = runBlocking {
                withTimeout(30_000) {
                    SshConnection.connect(server.target, listOf(SshCredential.Password(PASSWORD)), InMemoryKnownHosts(), ScriptedInteraction())
                }
            }
            connection.use {
                connection.openShell("xterm-256color", 80, 24, 0, 0, emptyMap(), agent)
                val shell = shells.single()
                assertTrue(shell.started.await(10, TimeUnit.SECONDS))
                val socket = assertNotNull(shell.env["SSH_AUTH_SOCK"], "the server set up agent forwarding")

                // What ssh or git on the server does: talk to the agent behind SSH_AUTH_SOCK.
                PropertyResolverUtils.updateProperty(server.server, "SSH_AUTH_SOCK", socket)
                val session = server.server.activeSessions.single()
                server.server.agentFactory.createClient(session, server.server).use { client ->
                    val identities = client.identities.toList()
                    assertEquals(listOf("phone", "laptop"), identities.map { it.value })
                    val data = "session data to sign".toByteArray()

                    val (rsaAlgorithm, rsaSignature) = client.sign(session, identities[1].key, "rsa-sha2-512", data)
                    assertEquals("rsa-sha2-512", rsaAlgorithm)
                    val verifier = Signature.getInstance("SHA512withRSA").apply {
                        initVerify(identities[1].key)
                        update(data)
                    }
                    assertTrue(verifier.verify(rsaSignature))

                    val (edAlgorithm, edSignature) = client.sign(session, identities[0].key, "ssh-ed25519", data)
                    assertEquals("ssh-ed25519", edAlgorithm)
                    val blob = Base64.getDecoder().decode(ed25519.publicKey.split(' ')[1])
                    val edVerifier = Ed25519Signer().apply {
                        init(false, Ed25519PublicKeyParameters(blob, blob.size - 32))
                        update(data, 0, data.size)
                    }
                    assertTrue(edVerifier.verifySignature(edSignature))
                }
            }
        }
    }
}
