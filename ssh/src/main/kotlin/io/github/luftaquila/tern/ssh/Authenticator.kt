// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.Service
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.method.AbstractAuthMethod
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthNone
import net.schmizz.sshj.userauth.method.AuthPublickey
import java.security.KeyPair

/** "password" method with a fixed password; a server-side password change request fails cleanly. */
internal class PasswordMethod(private val password: String) : AbstractAuthMethod("password") {
    @Volatile
    var changeRequested: Boolean = false
        private set

    override fun buildReq(): SSHPacket =
        super.buildReq()
            .putBoolean(false) // not a password change
            .putSensitiveString(password.toCharArray())

    override fun handle(msg: Message, buf: SSHPacket) {
        if (msg == Message.USERAUTH_60) { // SSH_MSG_USERAUTH_PASSWD_CHANGEREQ
            changeRequested = true
            throw UserAuthException("The server requires a password change")
        }
        super.handle(msg, buf)
    }
}

/**
 * RFC 4256 keyboard-interactive. Unlike sshj's AuthKeyboardInteractive it reads a whole
 * INFO_REQUEST before answering, so all prompts of a round are shown together. [respond]
 * runs on sshj's reader thread; returning null cancels the authentication.
 */
internal class KeyboardInteractiveMethod(
    private val respond: (name: String, instruction: String, prompts: List<KeyboardInteractivePrompt>) -> List<String>?,
) : AbstractAuthMethod("keyboard-interactive") {
    @Volatile
    var cancelled: Boolean = false
        private set

    override fun buildReq(): SSHPacket =
        super.buildReq()
            .putString("") // language tag
            .putString("") // submethods

    override fun handle(msg: Message, buf: SSHPacket) {
        if (msg != Message.USERAUTH_60) { // SSH_MSG_USERAUTH_INFO_REQUEST
            super.handle(msg, buf)
            return
        }
        val name: String
        val instruction: String
        val prompts: List<KeyboardInteractivePrompt>
        try {
            name = buf.readString()
            instruction = buf.readString()
            buf.readString() // language tag
            val count = buf.readUInt32AsInt()
            prompts = List(count) { KeyboardInteractivePrompt(buf.readString(), buf.readBoolean()) }
        } catch (e: Buffer.BufferException) {
            throw UserAuthException(e)
        }
        // Rounds without prompts (e.g. a final notice) are answered without bothering the user.
        val answers = if (prompts.isEmpty()) emptyList() else respond(name, instruction, prompts)
        if (answers == null) {
            cancelled = true
            throw UserAuthException("Keyboard-interactive authentication cancelled")
        }
        val reply = SSHPacket(Message.USERAUTH_INFO_RESPONSE).putUInt32(prompts.size.toLong())
        for (i in prompts.indices) reply.putSensitiveString(answers.getOrElse(i) { "" }.toCharArray())
        params.transport.write(reply)
    }
}

/**
 * Drives user authentication: a "none" probe (learns the allowed methods), the configured
 * private keys, the configured passwords, then interactive keyboard-interactive / password
 * prompts. Partial success (multi-factor) simply continues with the methods the server
 * still allows.
 */
internal class Authenticator(
    private val client: SSHClient,
    private val target: SshTarget,
    private val credentials: List<SshCredential>,
    private val interaction: SshInteraction,
    private val bridge: CallbackBridge,
    private val options: ConnectOptions,
    private val timeouts: Connector.Timeouts,
) {
    private val userAuth = client.userAuth
    private val nextService = client.connection as Service
    private var allowed: Set<String> = emptySet()
    private var lastError: UserAuthException? = null
    private var passwordChangeRequested = false
    private val unusedPasswords = ArrayDeque(credentials.filterIsInstance<SshCredential.Password>().map { it.password })

    suspend fun authenticate() {
        if (attempt(AuthNone(), timeouts.normalMillis)) return

        for (key in credentials.filterIsInstance<SshCredential.PrivateKey>()) {
            if (METHOD_PUBLICKEY !in allowed) break
            val pair = loadPrivateKey(key, interaction) ?: continue
            // The certificate first; a certificate of another key is left out.
            val certificate = key.certificate?.let { Certified.forKey(it, pair) }
            if (certificate != null && attempt(AuthPublickey(CertifiedKey(pair, certificate)), timeouts.normalMillis)) return
            if (METHOD_PUBLICKEY !in allowed) break
            if (attempt(AuthPublickey(KeyPairWrapper(pair)), timeouts.normalMillis)) return
        }

        for (password in credentials.filterIsInstance<SshCredential.Password>()) {
            if (METHOD_PASSWORD !in allowed) break
            unusedPasswords.remove(password.password)
            if (attempt(PasswordMethod(password.password), timeouts.normalMillis)) return
        }

        var interactiveRounds = 0
        var passwordPrompts = 0
        val max = options.maxPasswordAttempts.coerceAtLeast(1)
        while (true) {
            when {
                METHOD_KEYBOARD_INTERACTIVE in allowed && interactiveRounds < max -> {
                    interactiveRounds++
                    val method = KeyboardInteractiveMethod(::answerKeyboardInteractive)
                    val ok = attempt(method, timeouts.interactiveMillis)
                    if (method.cancelled) throw SshCancelledException()
                    if (ok) return
                }
                METHOD_PASSWORD in allowed && passwordPrompts < max -> {
                    passwordPrompts++
                    val password = interaction.requestPassword(target, passwordPrompts)
                        ?: throw SshCancelledException()
                    if (attempt(PasswordMethod(password), timeouts.normalMillis)) return
                }
                else -> break
            }
        }

        val reason = when {
            passwordChangeRequested -> "the server requires a password change"
            else -> "server allows: ${allowed.joinToString(", ").ifEmpty { "nothing" }}"
        }
        throw SshAuthenticationException("Authentication failed for ${target.username}@${target.host} ($reason)")
            .also { e -> lastError?.let { e.addSuppressed(it) } }
    }

    /** Runs one method; refreshes [allowed] from the server's USERAUTH_FAILURE afterwards. */
    private fun attempt(method: AuthMethod, timeoutMillis: Int): Boolean {
        val ok = try {
            userAuth.authenticate(target.username, nextService, method, timeoutMillis)
        } catch (e: UserAuthException) {
            lastError = e
            false
        }
        if (method is PasswordMethod && method.changeRequested) passwordChangeRequested = true
        if (!ok) allowed = userAuth.allowedMethods.toSet()
        return ok
    }

    /** Called on sshj's reader thread for each INFO_REQUEST round. */
    private fun answerKeyboardInteractive(
        name: String,
        instruction: String,
        prompts: List<KeyboardInteractivePrompt>,
    ): List<String>? {
        val single = prompts.singleOrNull()
        if (single != null && !single.echo && isPasswordPrompt(single.text)) {
            val stored = unusedPasswords.removeFirstOrNull()
            if (stored != null) return listOf(stored)
        }
        return bridge.call { interaction.keyboardInteractive(name, instruction, prompts) }
    }

    private fun isPasswordPrompt(text: String): Boolean {
        val t = text.lowercase()
        return "password" in t || "passphrase" in t || "암호" in t || "비밀번호" in t
    }

    companion object {
        const val METHOD_PUBLICKEY = "publickey"
        const val METHOD_PASSWORD = "password"
        const val METHOD_KEYBOARD_INTERACTIVE = "keyboard-interactive"
    }
}

private const val MAX_PASSPHRASE_PROMPTS = 3

/**
 * Loads [key], asking [interaction] for the passphrase (up to three times) when it is encrypted
 * and none is stored. Null when the user skips the key; [SshException] when it cannot be read.
 */
internal suspend fun loadPrivateKey(key: SshCredential.PrivateKey, interaction: SshInteraction): KeyPair? {
    if (!KeyFiles.isEncrypted(key.privateKey)) {
        return try {
            KeyFiles.load(key.privateKey, null)
        } catch (e: SshException) {
            throw SshException("Private key \"${key.name}\" could not be loaded: ${e.message}", e)
        }
    }
    var passphrase = key.passphrase
    var prompts = 0
    while (true) {
        if (passphrase == null) {
            if (prompts >= MAX_PASSPHRASE_PROMPTS) return null
            prompts++
            passphrase = interaction.requestPassphrase(key.name, prompts) ?: return null
        }
        try {
            return KeyFiles.load(key.privateKey, passphrase)
        } catch (e: KeyPassphraseException) {
            passphrase = null
        } catch (e: SshException) {
            throw SshException("Private key \"${key.name}\" could not be loaded: ${e.message}", e)
        }
    }
}
