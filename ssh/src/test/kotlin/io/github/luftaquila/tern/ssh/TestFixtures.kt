// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import kotlinx.coroutines.delay
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.PtyMode
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

const val USER = "alice"
const val PASSWORD = "s3cret"
const val HOST = "127.0.0.1"

fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** In-process MINA SSHD server bound to 127.0.0.1 on a random port. */
class TestServer(
    vararg hostKeys: KeyPair = arrayOf(ecKeyPair()),
    configure: SshServer.() -> Unit,
) : Closeable {
    val hostKeyPairs: List<KeyPair> = hostKeys.toList()
    val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = HOST
        port = 0
        keyPairProvider = KeyPairProvider.wrap(hostKeyPairs)
        configure()
        start()
    }
    val port: Int get() = server.port
    val target: SshTarget get() = SshTarget(HOST, port, USER)

    override fun close() {
        server.stop(true)
    }
}

/** "type base64" of a public key (MINA encoding), for comparing against our key lines. */
fun opensshKeyId(key: PublicKey): String = PublicKeyEntry.toString(key).split(' ').take(2).joinToString(" ")

fun keyId(authorizedKeysLine: String): String = authorizedKeysLine.trim().split(' ').take(2).joinToString(" ")

class InMemoryKnownHosts(private val authorities: List<HostCertificateAuthority> = emptyList()) : KnownHostsStore {
    val entries = CopyOnWriteArrayList<KnownHost>()

    override fun find(host: String, port: Int): List<KnownHost> = entries.filter { it.host == host && it.port == port }

    override fun certificateAuthorities(): List<HostCertificateAuthority> = authorities

    override fun save(entry: KnownHost) {
        entries.removeIf { it.host == entry.host && it.port == entry.port && it.keyType == entry.keyType }
        entries.add(entry)
    }
}

/** Scripted user: answers are consumed in order; an exhausted queue means "cancel". */
class ScriptedInteraction(
    var acceptHostKey: Boolean = true,
    var hostKeyDelayMillis: Long = 0,
    passwords: List<String?> = emptyList(),
    passphrases: List<String?> = emptyList(),
    keyboardInteractive: List<List<String>?> = emptyList(),
) : SshInteraction {
    private val passwords = ArrayDeque(passwords)
    private val passphrases = ArrayDeque(passphrases)
    private val kiAnswers = ArrayDeque(keyboardInteractive)

    val hostKeyPrompts = CopyOnWriteArrayList<Triple<HostKeyInfo, HostKeyStatus, List<KnownHost>>>()
    val passwordPrompts = AtomicInteger()
    val passphrasePrompts = CopyOnWriteArrayList<Pair<String, Int>>()
    val kiRounds = CopyOnWriteArrayList<Triple<String, String, List<KeyboardInteractivePrompt>>>()

    @Volatile
    var hostKeyThread: String? = null

    override suspend fun confirmHostKey(info: HostKeyInfo, status: HostKeyStatus, previous: List<KnownHost>): Boolean {
        hostKeyThread = Thread.currentThread().name
        hostKeyPrompts.add(Triple(info, status, previous))
        if (hostKeyDelayMillis > 0) delay(hostKeyDelayMillis)
        return acceptHostKey
    }

    override suspend fun requestPassword(target: SshTarget, attempt: Int): String? {
        passwordPrompts.incrementAndGet()
        return synchronized(passwords) { passwords.removeFirstOrNull() }
    }

    override suspend fun requestPassphrase(keyName: String, attempt: Int): String? {
        passphrasePrompts.add(keyName to attempt)
        return synchronized(passphrases) { passphrases.removeFirstOrNull() }
    }

    override suspend fun keyboardInteractive(
        name: String,
        instruction: String,
        prompts: List<KeyboardInteractivePrompt>,
    ): List<String>? {
        kiRounds.add(Triple(name, instruction, prompts))
        return synchronized(kiAnswers) { kiAnswers.removeFirstOrNull() }
    }
}

/**
 * Test shell: echoes stdin, records the environment / PTY modes it was started with and
 * window-change signals, and exits with status 7 on the line "exit".
 */
class RecordingShell : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var exitCallback: ExitCallback

    @Volatile
    var env: Map<String, String> = emptyMap()

    @Volatile
    var ptyModes: Map<PtyMode, Int> = emptyMap()
    val windowChanges = LinkedBlockingQueue<Pair<String?, String?>>()
    val started = java.util.concurrent.CountDownLatch(1)

    override fun setInputStream(`in`: InputStream) {
        input = `in`
    }

    override fun setOutputStream(out: OutputStream) {
        output = out
    }

    override fun setErrorStream(err: OutputStream) {}

    override fun setExitCallback(callback: ExitCallback) {
        exitCallback = callback
    }

    override fun start(channel: ChannelSession, env: Environment) {
        this.env = HashMap(env.env)
        this.ptyModes = HashMap(env.ptyModes)
        env.addSignalListener(
            SignalListener { _: Channel, _: Signal ->
                windowChanges.add(env.env[Environment.ENV_COLUMNS] to env.env[Environment.ENV_LINES])
            },
            Signal.WINCH,
        )
        started.countDown()
        thread(isDaemon = true, name = "recording-shell") {
            val buffer = ByteArray(4096)
            val line = StringBuilder()
            try {
                loop@ while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    output.flush()
                    for (b in String(buffer, 0, n, Charsets.UTF_8)) {
                        if (b == '\r' || b == '\n') {
                            if (line.toString() == "exit") {
                                exitCallback.onExit(7)
                                return@thread
                            }
                            line.setLength(0)
                        } else {
                            line.append(b)
                        }
                    }
                }
            } catch (_: IOException) {
            }
            exitCallback.onExit(0)
        }
    }

    override fun destroy(channel: ChannelSession) {}
}

/** Writes [text] to a fresh temp file with mode 600 (ssh-keygen refuses group/world readable keys). */
fun privateKeyFile(dir: Path, name: String, text: String): File {
    val file = dir.resolve(name)
    Files.write(file, text.toByteArray())
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    return file.toFile()
}

val SSH_KEYGEN: File? = listOf("/usr/bin/ssh-keygen", "/opt/homebrew/bin/ssh-keygen", "/usr/local/bin/ssh-keygen")
    .map(::File)
    .firstOrNull { it.canExecute() }

/** Runs a command, returning stdout; fails on a non-zero exit. */
fun run(vararg command: String): String {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
    check(process.waitFor(60, TimeUnit.SECONDS)) { "timeout: ${command.joinToString(" ")}" }
    check(process.exitValue() == 0) { "${command.joinToString(" ")} failed (${process.exitValue()}): $out" }
    return out
}
