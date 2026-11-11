// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.session

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import io.github.luftaquila.tern.AppContainer
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.ForwardType
import io.github.luftaquila.tern.data.Host
import io.github.luftaquila.tern.ssh.ShellChannel
import io.github.luftaquila.tern.ssh.SshConnection
import io.github.luftaquila.tern.terminal.ColorSchemes
import io.github.luftaquila.tern.terminal.TerminalEmulator
import io.github.luftaquila.tern.terminal.TerminalListener
import io.github.luftaquila.tern.terminal.TerminalOutput
import io.github.luftaquila.tern.termview.TerminalBackend
import io.github.luftaquila.tern.ui.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.BindException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

sealed interface SessionState {
    data object Connecting : SessionState
    data object Connected : SessionState
    data class Disconnected(val message: String?) : SessionState
}

/**
 * One interactive SSH shell: owns the connection, the PTY channel and the emulator. Output is
 * read on a dedicated thread and fed to the emulator under [lock]; input is written on a
 * single writer thread so the UI never blocks on the network.
 */
class TerminalSession(
    val id: Long,
    host: Host,
    private val container: AppContainer,
) : TerminalBackend {

    var host: Host = host
        private set

    override val lock = Any()
    override val emulator: TerminalEmulator

    val prompts = PromptBridge()

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _title = MutableStateFlow(host.displayName)
    val title: StateFlow<String> = _title.asStateFlow()

    /** Last directory reported by the shell via OSC 7 (used to open SFTP there). */
    private val _workingDirectory = MutableStateFlow<String?>(null)
    val workingDirectory: StateFlow<String?> = _workingDirectory.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tern-writer-$id").apply { isDaemon = true }
    }

    @Volatile
    var connection: SshConnection? = null
        private set

    @Volatile
    private var shell: ShellChannel? = null
    private var connectJob: Job? = null

    @Volatile
    private var closed = false
    private var lastBell = 0L

    // Size the view asked for; used when the shell is (re)opened.
    @Volatile private var columns = 80
    @Volatile private var rows = 24
    @Volatile private var widthPx = 0
    @Volatile private var heightPx = 0

    init {
        val settings = container.settings.value
        emulator = TerminalEmulator(
            columns = 80,
            rows = 24,
            scrollbackLines = settings.scrollbackLines,
            colorScheme = ColorSchemes.byId(settings.colorSchemeId),
            output = TerminalOutput { data -> write(data) },
        )
        emulator.listener = object : TerminalListener {
            override fun onTitleChanged(title: String) {
                _title.value = title.ifBlank { this@TerminalSession.host.displayName }
            }

            override fun onBell() = bell()

            override fun onClipboardWrite(text: String) {
                mainHandler.post {
                    container.context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
                }
            }

            override fun onWorkingDirectoryChanged(path: String) {
                _workingDirectory.value = path
            }
        }
    }

    // ---- TerminalBackend --------------------------------------------------------------

    override fun write(data: ByteArray) {
        val sh = shell ?: return
        if (data.isEmpty()) return
        try {
            writer.execute {
                try {
                    sh.output.write(data)
                } catch (e: IOException) {
                    Log.w(TAG, "write failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Session closed.
        }
    }

    override fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        this.columns = columns
        this.rows = rows
        this.widthPx = widthPx
        this.heightPx = heightPx
        synchronized(lock) {
            emulator.resize(columns, rows)
            emulator.setPixelSize(widthPx, heightPx)
        }
        notifyScreen()
        val sh = shell ?: return
        try {
            writer.execute { runCatching { sh.resize(columns, rows, widthPx, heightPx) } }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun addScreenListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeScreenListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyScreen() {
        for (l in listeners) l()
    }

    // ---- lifecycle --------------------------------------------------------------------

    fun connect() {
        if (closed) return
        connectJob?.cancel()
        connectJob = scope.launch { runConnection() }
    }

    fun reconnect() {
        if (_state.value !is SessionState.Disconnected) return
        container.hosts.get(host.id)?.let { host = it }
        printStatus("\r\n")
        connect()
    }

    /** Ends the session for good. */
    fun close() {
        if (closed) return
        closed = true
        prompts.cancelPending()
        scope.cancel()
        val sh = shell
        val conn = connection
        shell = null
        connection = null
        writer.execute {
            runCatching { sh?.close() }
            runCatching { conn?.close() }
        }
        writer.shutdown()
        listeners.clear()
    }

    private suspend fun runConnection() {
        _state.value = SessionState.Connecting
        val context = container.context
        val connector = HostConnector(container, prompts)
        var conn: SshConnection? = null
        try {
            conn = connector.connect(host) { hop, via ->
                val where = "${hop.hostname}:${hop.port}"
                val text = if (via == null) {
                    context.getString(R.string.status_connecting, where)
                } else {
                    context.getString(R.string.status_connecting_via, where, via.displayName)
                }
                printStatus(text + "\r\n")
                connector.keyWithInvalidCertificate(hop)?.let { key ->
                    printStatus(context.getString(R.string.status_certificate_invalid, key) + "\r\n")
                }
            }
            container.hosts.get(host.id)?.let { host = it } // picks up a password saved while connecting
            if (closed) {
                conn.close()
                return
            }
            connection = conn
            conn.setOnDisconnect { cause -> if (cause != null) onEnded(describeError(context, cause)) }
            startForwards(conn)
            val env = buildMap {
                if (host.lang.isNotBlank()) put("LANG", host.lang)
                // iTerm2's way of being recognized across SSH (sshd usually accepts LC_*):
                // programs such as gitoui then draw images with the iTerm2 protocol.
                if (container.settings.value.advertiseInlineImages) put("LC_TERMINAL", "iTerm2")
            }
            val agent = if (host.agentForwarding) connector.agent() else null
            val sh = conn.openShell(host.termType, columns, rows, widthPx, heightPx, env, agent)
            shell = sh
            _state.value = SessionState.Connected
            container.hosts.markUsed(host.id)
            if (host.startupCommand.isNotBlank()) {
                write((host.startupCommand.trimEnd() + "\r").toByteArray(Charsets.UTF_8))
            }
            startReader(sh)
        } catch (e: CancellationException) {
            runCatching { conn?.close() }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            runCatching { conn?.close() }
            connection = null
            onEnded(describeError(container.context, e))
        }
    }

    private fun startReader(sh: ShellChannel) {
        thread(name = "tern-reader-$id", isDaemon = true) {
            val buf = ByteArray(32 * 1024)
            val input = sh.input
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    -1
                }
                if (n < 0) break
                if (n == 0) continue
                synchronized(lock) { emulator.feed(buf, 0, n) }
                notifyScreen()
            }
            if (shell === sh) {
                val status = sh.exitStatus
                val msg = if (status != null) {
                    container.context.getString(R.string.status_exited, status)
                } else {
                    container.context.getString(R.string.status_disconnected)
                }
                onEnded(msg)
            }
        }
    }

    private fun onEnded(message: String) {
        if (closed) return
        if (_state.value is SessionState.Disconnected) return
        val sh = shell
        val conn = connection
        shell = null
        connection = null
        try {
            writer.execute {
                runCatching { sh?.close() }
                runCatching { conn?.close() }
            }
        } catch (_: RejectedExecutionException) {
        }
        _state.value = SessionState.Disconnected(message)
        printStatus("\r\n\u001b[0m\u001b[2m[$message]\u001b[0m\r\n")
    }

    private fun printStatus(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(lock) { emulator.feed(bytes) }
        notifyScreen()
    }

    private fun dim(text: String) = "\u001b[2m$text\u001b[0m\r\n"

    /** Opens the host's port forwards; each reports in the terminal whether it works. They end with the connection. */
    private fun startForwards(conn: SshConnection) {
        val context = container.context
        for (rule in host.forwards) {
            val destination = "${rule.destHost}:${rule.destPort}"
            val message = try {
                when (rule.type) {
                    ForwardType.LOCAL -> {
                        conn.forwardLocal(rule.port, rule.destHost, rule.destPort)
                        context.getString(R.string.forward_local_started, rule.port, destination)
                    }
                    ForwardType.REMOTE -> {
                        conn.forwardRemote(rule.port, rule.destHost, rule.destPort)
                        context.getString(R.string.forward_remote_started, rule.port, destination)
                    }
                    ForwardType.DYNAMIC -> {
                        conn.forwardDynamic(rule.port)
                        context.getString(R.string.forward_dynamic_started, rule.port)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "forward ${rule.type} ${rule.port} failed", e)
                val reason = when {
                    rule.type == ForwardType.REMOTE -> context.getString(R.string.forward_refused)
                    generateSequence(e as Throwable) { it.cause }.any { it is BindException } -> context.getString(R.string.forward_port_in_use)
                    else -> describeError(context, e)
                }
                context.getString(R.string.forward_failed, rule.port, reason)
            }
            printStatus(dim(message))
        }
    }

    private fun bell() {
        if (!container.settings.value.vibrateOnBell) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBell < 300) return
        lastBell = now
        mainHandler.post {
            val vibrator = container.context.getSystemService(Vibrator::class.java) ?: return@post
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun applyColorScheme(id: String) {
        synchronized(lock) { emulator.colorScheme = ColorSchemes.byId(id) }
        notifyScreen()
    }

    companion object {
        private const val TAG = "TerminalSession"
    }
}
