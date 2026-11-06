// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.sftp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import io.github.luftaquila.tern.AppContainer
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.session.HostConnector
import io.github.luftaquila.tern.session.PromptBridge
import io.github.luftaquila.tern.ssh.RemoteFile
import io.github.luftaquila.tern.ssh.RemoteFileSystem
import io.github.luftaquila.tern.ssh.SshCancelledException
import io.github.luftaquila.tern.ssh.SshConnection
import io.github.luftaquila.tern.ui.describeError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class SortMode { NAME, SIZE, MODIFIED }

sealed interface SftpState {
    data object Connecting : SftpState
    data object Ready : SftpState
    data class Failed(val message: String) : SftpState
}

enum class TransferStatus { RUNNING, DONE, FAILED, CANCELLED }

@Stable
class Transfer(val id: Long, val name: String, val upload: Boolean) {
    var transferred by mutableLongStateOf(0L)
    var total by mutableLongStateOf(-1L)
    var status by mutableStateOf(TransferStatus.RUNNING)
    var error by mutableStateOf<String?>(null)

    @Volatile
    var cancelRequested = false
}

/**
 * State and operations of one SFTP browser screen. Reuses the terminal session's connection
 * when opened from a terminal; otherwise opens (and later closes) its own connection.
 */
@Stable
class SftpController(
    private val context: Context,
    private val container: AppContainer,
    private val hostId: String,
    private val sessionId: Long?,
) {
    val prompts = PromptBridge()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val meta = Dispatchers.IO.limitedParallelism(1)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val transfersDispatcher = Dispatchers.IO.limitedParallelism(2)

    var state by mutableStateOf<SftpState>(SftpState.Connecting)
        private set
    var path by mutableStateOf("")
        private set
    var entries by mutableStateOf<List<RemoteFile>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var showHidden by mutableStateOf(false)
    var sort by mutableStateOf(SortMode.NAME)
    val transfers = mutableStateListOf<Transfer>()

    /** Folders visited before the current one, for the back gesture. */
    private val history = ArrayDeque<String>()
    var canGoBack by mutableStateOf(false)
        private set

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val ids = AtomicLong(1)
    private var ownConnection: SshConnection? = null
    private var fs: RemoteFileSystem? = null

    val hostLabel: String get() = container.hosts.get(hostId)?.displayName ?: ""

    fun start() {
        scope.launch { connect() }
    }

    fun retry() = start()

    private suspend fun connect() {
        state = SftpState.Connecting
        try {
            val session = sessionId?.let { container.sessions.find(it) }
            val shared = session?.connection?.takeIf { it.isConnected }
            val connection = shared ?: openOwnConnection()
            val opened = withContext(meta) { connection.openFileSystem() }
            fs = opened
            val start = session?.workingDirectory?.value ?: withContext(meta) { opened.home() }
            state = SftpState.Ready
            load(start)
        } catch (e: Exception) {
            state = SftpState.Failed(describeError(context, e))
        }
    }

    private suspend fun openOwnConnection(): SshConnection {
        val host = container.hosts.get(hostId) ?: throw IllegalStateException(context.getString(R.string.error_host_missing))
        val connection = HostConnector(container, prompts).connect(host)
        ownConnection = connection
        return connection
    }

    fun close() {
        prompts.cancelPending()
        transfers.forEach { it.cancelRequested = true }
        scope.cancel()
        val f = fs
        val own = ownConnection
        fs = null
        ownConnection = null
        Thread {
            runCatching { f?.close() }
            runCatching { own?.close() }
        }.start()
    }

    // ---- browsing --------------------------------------------------------------------

    fun load(target: String, remember: Boolean = true) {
        val f = fs ?: return
        scope.launch {
            loading = true
            try {
                val (canonical, list) = withContext(meta) {
                    val c = f.canonicalize(target)
                    c to f.list(c)
                }
                if (remember && path.isNotEmpty() && canonical != path) {
                    history.addLast(path)
                    if (history.size > 100) history.removeFirst()
                }
                canGoBack = history.isNotEmpty()
                path = canonical
                entries = list
            } catch (e: Exception) {
                toast(describeError(context, e))
            } finally {
                loading = false
            }
        }
    }

    fun refresh() = load(path, remember = false)

    /** Returns to the previously visited folder. @return false if there is none. */
    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        canGoBack = history.isNotEmpty()
        load(previous, remember = false)
        return true
    }

    fun up() {
        if (path == "/" || path.isEmpty()) return
        load(parentOf(path))
    }

    fun visible(): List<RemoteFile> {
        val filtered = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
        val comparator: Comparator<RemoteFile> = when (sort) {
            SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.SIZE -> compareByDescending<RemoteFile> { it.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.MODIFIED -> compareByDescending<RemoteFile> { it.modifiedEpochSeconds }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        return filtered.sortedWith(compareByDescending<RemoteFile> { it.isDirectoryLike }.then(comparator))
    }

    // ---- file operations --------------------------------------------------------------

    private fun operate(successMessage: Int?, block: (RemoteFileSystem) -> Unit) {
        val f = fs ?: return
        scope.launch {
            try {
                withContext(meta) { block(f) }
                successMessage?.let { toast(context.getString(it)) }
                refresh()
            } catch (e: Exception) {
                toast(describeError(context, e))
            }
        }
    }

    fun mkdir(name: String) = operate(null) { it.mkdir(join(path, name)) }

    fun rename(file: RemoteFile, newName: String) = operate(null) { it.rename(file.path, join(parentOf(file.path), newName)) }

    fun delete(file: RemoteFile) = operate(R.string.sftp_deleted) { it.delete(file.path, recursive = file.type == io.github.luftaquila.tern.ssh.RemoteFileType.DIRECTORY) }

    fun chmod(file: RemoteFile, mode: Int) = operate(null) { it.chmod(file.path, mode) }

    // ---- transfers -------------------------------------------------------------------

    fun download(file: RemoteFile, target: Uri) {
        val f = fs ?: return
        val transfer = Transfer(ids.getAndIncrement(), file.name, upload = false).also { it.total = file.size }
        transfers.add(0, transfer)
        scope.launch {
            try {
                withContext(transfersDispatcher) {
                    val out = context.contentResolver.openOutputStream(target, "wt") ?: error("cannot open output")
                    out.use { stream ->
                        f.download(file.path, stream, progressOf(transfer))
                    }
                }
                transfer.status = TransferStatus.DONE
            } catch (e: Exception) {
                finishWithError(transfer, e)
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
            }
        }
    }

    fun upload(uris: List<Uri>) {
        val f = fs ?: return
        val dir = path
        for (uri in uris) {
            val (name, size) = queryNameAndSize(uri)
            val transfer = Transfer(ids.getAndIncrement(), name, upload = true).also { it.total = size }
            transfers.add(0, transfer)
            scope.launch {
                try {
                    withContext(transfersDispatcher) {
                        val input = context.contentResolver.openInputStream(uri) ?: error("cannot open input")
                        input.use { stream -> f.upload(stream, size, join(dir, name), progressOf(transfer)) }
                    }
                    transfer.status = TransferStatus.DONE
                    if (path == dir) refresh()
                } catch (e: Exception) {
                    finishWithError(transfer, e)
                }
            }
        }
    }

    /** Downloads to the app cache and hands the file to another app. */
    fun open(file: RemoteFile) {
        val f = fs ?: return
        val transfer = Transfer(ids.getAndIncrement(), file.name, upload = false).also { it.total = file.size }
        transfers.add(0, transfer)
        scope.launch {
            try {
                val local = withContext(transfersDispatcher) {
                    val dir = File(context.cacheDir, "sftp").apply { mkdirs() }
                    val out = File(dir, file.name)
                    out.outputStream().use { stream -> f.download(file.path, stream, progressOf(transfer)) }
                    out
                }
                transfer.status = TransferStatus.DONE
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", local)
                val ext = file.name.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/plain"
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    toast(context.getString(R.string.sftp_no_app))
                }
            } catch (e: Exception) {
                finishWithError(transfer, e)
            }
        }
    }

    fun cancel(transfer: Transfer) {
        transfer.cancelRequested = true
    }

    fun dismiss(transfer: Transfer) {
        transfers.remove(transfer)
    }

    fun clearFinished() {
        transfers.removeAll { it.status != TransferStatus.RUNNING }
    }

    private fun progressOf(transfer: Transfer): io.github.luftaquila.tern.ssh.TransferProgress {
        var last = 0L
        return io.github.luftaquila.tern.ssh.TransferProgress { done, total ->
            val now = System.nanoTime()
            if (now - last > 80_000_000L || done == total) {
                last = now
                transfer.transferred = done
                if (total >= 0) transfer.total = total
            }
            !transfer.cancelRequested
        }
    }

    private fun finishWithError(transfer: Transfer, e: Exception) {
        if (e is SshCancelledException || transfer.cancelRequested) {
            transfer.status = TransferStatus.CANCELLED
        } else {
            transfer.status = TransferStatus.FAILED
            transfer.error = describeError(context, e)
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    private fun toast(message: String) {
        _messages.trySend(message)
    }

    companion object {
        fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

        fun parentOf(path: String): String {
            val trimmed = path.trimEnd('/')
            val i = trimmed.lastIndexOf('/')
            return if (i <= 0) "/" else trimmed.substring(0, i)
        }
    }
}
