// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostKeyTest {

    private val credentials = listOf(SshCredential.Password(PASSWORD))

    private fun server(vararg hostKeys: java.security.KeyPair) = TestServer(*hostKeys) {
        passwordAuthenticator = PasswordAuthenticator { _, p, _ -> p == PASSWORD }
        userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    @AfterTest
    fun resetTimeouts() {
        Connector.timeouts = Connector.Timeouts(normalMillis = 30_000, interactiveMillis = 10 * 60_000)
    }

    @Test
    fun unknownKeyAcceptedThenRemembered() {
        val hostKey = ecKeyPair()
        server(hostKey).use { server ->
            val store = InMemoryKnownHosts()
            val ui = ScriptedInteraction()
            blocking { SshConnection.connect(server.target, credentials, store, ui) }.use { c ->
                assertEquals(1, ui.hostKeyPrompts.size)
                val (info, status, previous) = ui.hostKeyPrompts[0]
                assertEquals(HostKeyStatus.UNKNOWN, status)
                assertTrue(previous.isEmpty())
                assertEquals("ecdsa-sha2-nistp256", info.keyType)
                assertEquals(HOST, info.host)
                assertEquals(server.port, info.port)
                // Same fingerprint as MINA computes for its own host key, same blob as OpenSSH text.
                assertEquals(KeyUtils.getFingerPrint(hostKey.public), info.fingerprintSha256)
                assertEquals(PublicKeyEntry.toString(hostKey.public).split(' ')[1], info.publicKeyBase64)
                assertEquals(info, c.hostKey)
                // The prompt ran on sshj's transport thread, not the caller's.
                assertTrue(ui.hostKeyThread!!.startsWith("sshj"), ui.hostKeyThread)
            }
            val saved = store.entries.single()
            assertEquals(HOST, saved.host)
            assertEquals(server.port, saved.port)
            assertEquals("ecdsa-sha2-nistp256", saved.keyType)

            // Second connection: trusted, no prompt.
            val ui2 = ScriptedInteraction(acceptHostKey = false)
            blocking { SshConnection.connect(server.target, credentials, store, ui2) }.close()
            assertTrue(ui2.hostKeyPrompts.isEmpty())
        }
    }

    @Test
    fun changedKeyIsReportedAndReplacedWhenAccepted() {
        server(ecKeyPair()).use { server ->
            val store = InMemoryKnownHosts()
            val stale = KnownHost(
                HOST, server.port, "ecdsa-sha2-nistp256",
                PublicKeyEntry.toString(ecKeyPair().public).split(' ')[1], 1L,
            )
            store.save(stale)
            val ui = ScriptedInteraction()
            blocking { SshConnection.connect(server.target, credentials, store, ui) }.close()
            val (info, status, previous) = ui.hostKeyPrompts.single()
            assertEquals(HostKeyStatus.CHANGED, status)
            assertEquals(listOf(stale), previous)
            assertEquals(info.publicKeyBase64, store.entries.single().publicKeyBase64)
        }
    }

    @Test
    fun rejectedKeyAborts() {
        server(ecKeyPair()).use { server ->
            val store = InMemoryKnownHosts()
            val ui = ScriptedInteraction(acceptHostKey = false)
            assertFailsWith<SshHostKeyRejectedException> {
                blocking { SshConnection.connect(server.target, credentials, store, ui) }
            }
            assertTrue(store.entries.isEmpty())
            assertEquals(1, ui.hostKeyPrompts.size)
        }
    }

    @Test
    fun knownKeyTypeIsNegotiatedWhenServerHasSeveral() {
        val ec = ecKeyPair()
        val rsa = rsaKeyPair()
        server(ec, rsa).use { server ->
            val store = InMemoryKnownHosts()
            // Only the RSA key is trusted; without findExistingAlgorithms the client would
            // negotiate ECDSA (preferred) and wrongly prompt.
            store.save(KnownHost(HOST, server.port, "ssh-rsa", PublicKeyEntry.toString(rsa.public).split(' ')[1], 1L))
            val ui = ScriptedInteraction(acceptHostKey = false)
            blocking { SshConnection.connect(server.target, credentials, store, ui) }.use { c ->
                assertEquals("ssh-rsa", c.hostKey.keyType)
            }
            assertTrue(ui.hostKeyPrompts.isEmpty())
        }
    }

    @Test
    fun slowHostKeyAnswerDoesNotTripKexTimeout() {
        server(ecKeyPair()).use { server ->
            // A user answering after the normal transport timeout must still connect.
            Connector.timeouts = Connector.Timeouts(normalMillis = 1_000, interactiveMillis = 30_000)
            val ui = ScriptedInteraction(hostKeyDelayMillis = 3_000)
            blocking { SshConnection.connect(server.target, credentials, InMemoryKnownHosts(), ui) }.use {
                assertTrue(it.isConnected)
            }

            // Control: with a short interactive timeout the same delay fails.
            Connector.timeouts = Connector.Timeouts(normalMillis = 1_000, interactiveMillis = 1_000)
            val e = assertFailsWith<SshException> {
                blocking { SshConnection.connect(server.target, credentials, InMemoryKnownHosts(), ScriptedInteraction(hostKeyDelayMillis = 3_000)) }
            }
            assertFalse(e is SshHostKeyRejectedException)
        }
    }

    @Test
    fun cancellingConnectCancelsPendingPrompt() {
        server(ecKeyPair()).use { server ->
            val promptStarted = CompletableDeferred<Unit>()
            val promptCancelled = CompletableDeferred<Unit>()
            val ui = object : SshInteraction by ScriptedInteraction() {
                override suspend fun confirmHostKey(info: HostKeyInfo, status: HostKeyStatus, previous: List<KnownHost>): Boolean {
                    promptStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        promptCancelled.complete(Unit)
                    }
                }
            }
            runBlocking {
                withTimeout(30_000) {
                    val job = async(Dispatchers.Default) {
                        SshConnection.connect(server.target, credentials, InMemoryKnownHosts(), ui)
                    }
                    promptStarted.await()
                    delay(200)
                    job.cancel()
                    promptCancelled.await()
                    val result = runCatching { job.await() }
                    assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException, "$result")
                }
            }
        }
    }

    @Test
    fun disconnectListenerFiresOnServerShutdownAndOnClose() {
        val server = server(ecKeyPair())
        val c = blocking { SshConnection.connect(server.target, credentials, InMemoryKnownHosts(), ScriptedInteraction()) }
        val cause = CompletableDeferred<Throwable?>()
        c.setOnDisconnect { cause.complete(it) }
        assertTrue(c.isConnected)
        server.server.stop(true)
        val reason = blocking { cause.await() }
        assertNotNull(reason)
        assertFalse(c.isConnected)
        c.close()

        // A clean close reports null, also to listeners registered afterwards.
        server(ecKeyPair()).use { s2 ->
            val c2 = blocking { SshConnection.connect(s2.target, credentials, InMemoryKnownHosts(), ScriptedInteraction()) }
            c2.close()
            assertFalse(c2.isConnected)
            val late = CompletableDeferred<Throwable?>()
            c2.setOnDisconnect { late.complete(it) }
            assertEquals(null, blocking { late.await() })
        }
    }
}
