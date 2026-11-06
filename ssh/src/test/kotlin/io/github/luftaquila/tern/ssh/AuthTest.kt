// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.session.ServerSession
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    private fun connect(server: TestServer, credentials: List<SshCredential>, ui: ScriptedInteraction, options: ConnectOptions = ConnectOptions()) =
        blocking { SshConnection.connect(server.target, credentials, InMemoryKnownHosts(), ui, options) }

    private fun passwordServer() = TestServer {
        passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == USER && p == PASSWORD }
        userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
    }

    private fun otpChallenge(vararg prompts: Pair<String, Boolean>) = object : KeyboardInteractiveAuthenticator {
        val responses = CopyOnWriteArrayList<List<String>>()
        override fun generateChallenge(session: ServerSession, username: String, lang: String?, subMethods: String?) =
            InteractiveChallenge().apply {
                interactionName = "Tern test"
                interactionInstruction = "Answer all prompts"
                languageTag = ""
                for ((text, echo) in prompts) addPrompt(text, echo)
            }

        override fun authenticate(session: ServerSession, username: String, responses: List<String>): Boolean {
            this.responses.add(responses)
            return username == USER && responses == expected
        }

        var expected: List<String> = emptyList()
    }

    @Test
    fun storedPassword() {
        passwordServer().use { server ->
            val ui = ScriptedInteraction()
            connect(server, listOf(SshCredential.Password(PASSWORD)), ui).use { c ->
                assertTrue(c.isConnected)
                assertEquals(server.target, c.target)
            }
            assertEquals(0, ui.passwordPrompts.get())
        }
    }

    @Test
    fun wrongStoredPasswordThenPrompt() {
        passwordServer().use { server ->
            val ui = ScriptedInteraction(passwords = listOf("nope", PASSWORD))
            connect(server, listOf(SshCredential.Password("bad")), ui).use { assertTrue(it.isConnected) }
            assertEquals(2, ui.passwordPrompts.get())
        }
    }

    @Test
    fun cancelledPasswordPrompt() {
        passwordServer().use { server ->
            val ui = ScriptedInteraction(passwords = emptyList())
            assertFailsWith<SshCancelledException> { connect(server, emptyList(), ui) }
            assertEquals(1, ui.passwordPrompts.get())
        }
    }

    @Test
    fun exhaustedPasswordAttempts() {
        passwordServer().use { server ->
            val ui = ScriptedInteraction(passwords = listOf("a", "b", "c", "d"))
            assertFailsWith<SshAuthenticationException> {
                connect(server, emptyList(), ui, ConnectOptions(maxPasswordAttempts = 3))
            }
            assertEquals(3, ui.passwordPrompts.get())
        }
    }

    @Test
    fun publicKeyForEveryGeneratedType() {
        for (type in SshKeyType.values()) {
            val key = SshKeys.generate(type, "test")
            val seen = CopyOnWriteArrayList<String>()
            TestServer {
                publickeyAuthenticator = PublickeyAuthenticator { u, k, _ ->
                    val id = opensshKeyId(k)
                    seen.add(id)
                    u == USER && id == keyId(key.publicKey)
                }
                userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
            }.use { server ->
                val ui = ScriptedInteraction()
                connect(server, listOf(SshCredential.PrivateKey("k", key.privateKey)), ui).use {
                    assertTrue(it.isConnected, type.name)
                }
                assertTrue(seen.contains(keyId(key.publicKey)), "$type: $seen")
            }
        }
    }

    @Test
    fun publicKeyFallsBackToNextKeyThenPassword() {
        val good = SshKeys.generate(SshKeyType.ED25519, "good")
        val other = SshKeys.generate(SshKeyType.ECDSA_P256, "other")
        TestServer {
            publickeyAuthenticator = PublickeyAuthenticator { _, k, _ -> opensshKeyId(k) == keyId(good.publicKey) }
            passwordAuthenticator = PasswordAuthenticator { _, p, _ -> p == PASSWORD }
            userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE, UserAuthPasswordFactory.INSTANCE)
        }.use { server ->
            val ui = ScriptedInteraction()
            connect(
                server,
                listOf(SshCredential.PrivateKey("other", other.privateKey), SshCredential.PrivateKey("good", good.privateKey)),
                ui,
            ).close()
            assertEquals(0, ui.passwordPrompts.get())

            // Unknown key only: the server still allows password, so the prompt follows.
            val ui2 = ScriptedInteraction(passwords = listOf(PASSWORD))
            connect(server, listOf(SshCredential.PrivateKey("other", other.privateKey)), ui2).close()
            assertEquals(1, ui2.passwordPrompts.get())
        }
    }

    @Test
    fun encryptedKeyPromptsForPassphrase() {
        val keygen = SSH_KEYGEN
        assumeTrue("ssh-keygen not available", keygen != null)
        val file = File(tmp.root, "id_enc")
        run(keygen!!.path, "-q", "-t", "ed25519", "-N", "open sesame", "-C", "enc", "-f", file.path)
        val pub = keyId(File(tmp.root, "id_enc.pub").readText())
        TestServer {
            publickeyAuthenticator = PublickeyAuthenticator { _, k, _ -> opensshKeyId(k) == pub }
            userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
        }.use { server ->
            // Wrong passphrase stored with the credential, wrong first answer, then correct.
            val ui = ScriptedInteraction(passphrases = listOf("wrong", "open sesame"))
            connect(server, listOf(SshCredential.PrivateKey("my key", file.readText(), "stale")), ui).close()
            assertEquals(listOf("my key" to 1, "my key" to 2), ui.passphrasePrompts.toList())

            // Correct stored passphrase: no prompt.
            val ui2 = ScriptedInteraction()
            connect(server, listOf(SshCredential.PrivateKey("my key", file.readText(), "open sesame")), ui2).close()
            assertTrue(ui2.passphrasePrompts.isEmpty())

            // Skipping the key (null) leaves no method → authentication failure.
            val ui3 = ScriptedInteraction(passphrases = emptyList())
            assertFailsWith<SshAuthenticationException> {
                connect(server, listOf(SshCredential.PrivateKey("my key", file.readText())), ui3)
            }
        }
    }

    @Test
    fun pemEcKeyWithExplicitCurveParametersAuthenticates() {
        val keygen = SSH_KEYGEN
        assumeTrue("ssh-keygen not available", keygen != null)
        // LibreSSL-based ssh-keygen writes SEC1 keys with explicit curve parameters.
        val file = File(tmp.root, "id_ec_pem")
        run(keygen!!.path, "-q", "-t", "ecdsa", "-b", "256", "-m", "PEM", "-N", "pem-pass-1", "-C", "pem", "-f", file.path)
        val pub = keyId(File(tmp.root, "id_ec_pem.pub").readText())
        TestServer {
            publickeyAuthenticator = PublickeyAuthenticator { _, k, _ -> opensshKeyId(k) == pub }
            userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
        }.use { server ->
            val ui = ScriptedInteraction()
            connect(server, listOf(SshCredential.PrivateKey("pem", file.readText(), "pem-pass-1")), ui).use {
                assertTrue(it.isConnected)
            }
        }
    }

    @Test
    fun keyboardInteractiveMultiplePrompts() {
        val ki = otpChallenge("Password: " to false, "Verification code: " to true).apply {
            expected = listOf(PASSWORD, "123456")
        }
        TestServer {
            keyboardInteractiveAuthenticator = ki
            userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
        }.use { server ->
            val ui = ScriptedInteraction(keyboardInteractive = listOf(listOf("wrong", "000000"), listOf(PASSWORD, "123456")))
            connect(server, emptyList(), ui).close()
            assertEquals(2, ui.kiRounds.size)
            val (name, instruction, prompts) = ui.kiRounds[0]
            assertEquals("Tern test", name)
            assertEquals("Answer all prompts", instruction)
            assertEquals(
                listOf(KeyboardInteractivePrompt("Password: ", false), KeyboardInteractivePrompt("Verification code: ", true)),
                prompts,
            )

            val cancelUi = ScriptedInteraction(keyboardInteractive = emptyList())
            assertFailsWith<SshCancelledException> { connect(server, emptyList(), cancelUi) }
        }
    }

    @Test
    fun keyboardInteractivePasswordIsAnsweredFromStoredPassword() {
        val ki = otpChallenge("Password: " to false).apply { expected = listOf(PASSWORD) }
        TestServer {
            keyboardInteractiveAuthenticator = ki
            userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
        }.use { server ->
            val ui = ScriptedInteraction()
            connect(server, listOf(SshCredential.Password(PASSWORD)), ui).close()
            assertTrue(ui.kiRounds.isEmpty())
            assertEquals(listOf(listOf(PASSWORD)), ki.responses.toList())
        }
    }

    @Test
    fun multiFactorPublicKeyThenKeyboardInteractive() {
        val key = SshKeys.generate(SshKeyType.ED25519, "mfa")
        val ki = otpChallenge("One-time code: " to true).apply { expected = listOf("424242") }
        TestServer {
            publickeyAuthenticator = PublickeyAuthenticator { _, k, _ -> opensshKeyId(k) == keyId(key.publicKey) }
            keyboardInteractiveAuthenticator = ki
            userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE)
            CoreModuleProperties.AUTH_METHODS.set(this, "publickey,keyboard-interactive")
        }.use { server ->
            val ui = ScriptedInteraction(keyboardInteractive = listOf(listOf("424242")))
            connect(server, listOf(SshCredential.PrivateKey("k", key.privateKey)), ui).use {
                assertTrue(it.isConnected)
            }
            assertEquals(1, ui.kiRounds.size)
        }
    }
}
