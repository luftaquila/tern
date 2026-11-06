// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.sftp

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.ssh.RemoteFile
import io.github.luftaquila.tern.ssh.RemoteFileType
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.formatDate
import io.github.luftaquila.tern.ui.formatPermissions
import io.github.luftaquila.tern.ui.formatSize
import io.github.luftaquila.tern.ui.terminal.PromptDialog
import io.github.luftaquila.tern.ui.theme.MonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(hostId: String, sessionId: Long?) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val controller = remember(hostId, sessionId) { SftpController(context.applicationContext, container, hostId, sessionId) }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.close() }
    }
    val prompt by controller.prompts.pending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(controller) {
        controller.messages.collect { snackbar.showSnackbar(it) }
    }

    // Back walks the folder history first, then leaves the screen.
    BackHandler(enabled = controller.canGoBack) { controller.back() }
    var selected by remember { mutableStateOf<RemoteFile?>(null) }
    var dialog by remember { mutableStateOf<SftpDialog?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    var menu by remember { mutableStateOf(false) }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingDownload
        pendingDownload = null
        if (uri != null && file != null) controller.download(file, uri)
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.upload(uris)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(controller.hostLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.sftp_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    if (controller.state == SftpState.Ready) {
                        IconButton(onClick = {
                            container.lock.beforeExternalPicker()
                            uploadLauncher.launch(arrayOf("*/*"))
                        }) {
                            Icon(Icons.Rounded.Upload, stringResource(R.string.sftp_upload))
                        }
                        IconButton(onClick = { dialog = SftpDialog.NewFolder }) {
                            Icon(Icons.Rounded.CreateNewFolder, stringResource(R.string.sftp_new_folder))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_refresh)) },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                    onClick = { menu = false; controller.refresh() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sftp_go_to)) },
                                    leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                                    onClick = { menu = false; dialog = SftpDialog.GoTo },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sftp_show_hidden)) },
                                    trailingIcon = { Checkbox(checked = controller.showHidden, onCheckedChange = null) },
                                    onClick = { controller.showHidden = !controller.showHidden },
                                )
                                HorizontalDivider()
                                listOf(
                                    SortMode.NAME to R.string.sftp_sort_name,
                                    SortMode.SIZE to R.string.sftp_sort_size,
                                    SortMode.MODIFIED to R.string.sftp_sort_modified,
                                ).forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        leadingIcon = { RadioButton(selected = controller.sort == mode, onClick = null) },
                                        onClick = { controller.sort = mode; menu = false },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val state = controller.state) {
                SftpState.Connecting -> CenterMessage {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.sftp_connecting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is SftpState.Failed -> CenterMessage {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { controller.retry() }) { Text(stringResource(R.string.action_retry)) }
                }
                SftpState.Ready -> {
                    Breadcrumbs(controller.path) { controller.load(it) }
                    Box(Modifier.fillMaxWidth().height(2.dp)) {
                        if (controller.loading) LinearProgressIndicator(Modifier.fillMaxSize())
                    }
                    val files = controller.visible()
                    val listState = rememberLazyListState()
                    LaunchedEffect(controller.path) { listState.scrollToItem(0) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        if (controller.path != "/") {
                            item(key = "..") {
                                FileRow(
                                    icon = Icons.Rounded.Folder,
                                    tint = MaterialTheme.colorScheme.primary,
                                    name = "..",
                                    meta = stringResource(R.string.sftp_parent),
                                    trailing = null,
                                    onClick = { controller.up() },
                                    onLongClick = null,
                                )
                            }
                        }
                        items(files, key = { it.path }) { file ->
                            FileRow(
                                icon = iconFor(file),
                                tint = if (file.isDirectoryLike) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                name = file.name,
                                meta = buildString {
                                    if (!file.isDirectoryLike) append(formatSize(file.size)).append(", ")
                                    append(formatDate(file.modifiedEpochSeconds))
                                    if (file.type == RemoteFileType.SYMLINK && file.linkTarget != null) append(" → ").append(file.linkTarget)
                                },
                                trailing = formatPermissions(file.permissions),
                                onClick = {
                                    if (file.isDirectoryLike) controller.load(file.path) else selected = file
                                },
                                onLongClick = { selected = file },
                            )
                        }
                        if (files.isEmpty() && !controller.loading) {
                            item {
                                Text(
                                    stringResource(R.string.sftp_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                )
                            }
                        }
                    }
                    if (controller.transfers.isNotEmpty()) TransfersPanel(controller)
                }
            }
        }
    }

    selected?.let { file ->
        FileActionsSheet(
            file = file,
            onDismiss = { selected = null },
            onOpen = { selected = null; controller.open(file) },
            onDownload = {
                selected = null
                pendingDownload = file
                container.lock.beforeExternalPicker()
                downloadLauncher.launch(file.name)
            },
            onRename = { selected = null; dialog = SftpDialog.Rename(file) },
            onChmod = { selected = null; dialog = SftpDialog.Chmod(file) },
            onCopyPath = {
                selected = null
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("path", file.path))
            },
            onDelete = { selected = null; dialog = SftpDialog.Delete(file) },
        )
    }

    when (val d = dialog) {
        null -> Unit
        SftpDialog.GoTo -> NameDialog(
            title = stringResource(R.string.sftp_go_to),
            initial = controller.path,
            allowSlash = true,
            onDismiss = { dialog = null },
            onConfirm = { controller.load(it); dialog = null },
        )
        SftpDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.sftp_new_folder),
            initial = "",
            onDismiss = { dialog = null },
            onConfirm = { controller.mkdir(it); dialog = null },
        )
        is SftpDialog.Rename -> NameDialog(
            title = stringResource(R.string.sftp_rename),
            initial = d.file.name,
            onDismiss = { dialog = null },
            onConfirm = { controller.rename(d.file, it); dialog = null },
        )
        is SftpDialog.Chmod -> ChmodDialog(
            file = d.file,
            onDismiss = { dialog = null },
            onConfirm = { controller.chmod(d.file, it); dialog = null },
        )
        is SftpDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.sftp_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (d.file.type == RemoteFileType.DIRECTORY) R.string.sftp_delete_folder_message else R.string.sftp_delete_file_message,
                        d.file.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { controller.delete(d.file); dialog = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    PromptDialog(prompt)
}

private sealed interface SftpDialog {
    data object NewFolder : SftpDialog
    data object GoTo : SftpDialog
    data class Rename(val file: RemoteFile) : SftpDialog
    data class Chmod(val file: RemoteFile) : SftpDialog
    data class Delete(val file: RemoteFile) : SftpDialog
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = remember(path) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        buildList {
            add("/" to "/")
            var acc = ""
            for (p in parts) {
                acc += "/$p"
                add(p to acc)
            }
        }
    }
    val state = rememberLazyListState()
    LaunchedEffect(segments.size) { if (segments.isNotEmpty()) state.scrollToItem(segments.lastIndex) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(segments) { i, (label, full) ->
            if (i > 1) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
            val last = i == segments.lastIndex
            Text(
                label,
                fontFamily = MonoFamily,
                style = MaterialTheme.typography.bodyMedium,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !last) { onNavigate(full) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FileRow(
    icon: ImageVector,
    tint: Color,
    name: String,
    meta: String,
    trailing: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!trailing.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

private fun iconFor(file: RemoteFile): ImageVector {
    if (file.type == RemoteFileType.SYMLINK && file.isDirectoryLike) return Icons.AutoMirrored.Rounded.DriveFileMove
    if (file.isDirectoryLike) return Icons.Rounded.Folder
    return when (file.name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic" -> Icons.Rounded.Image
        "mp4", "mkv", "mov", "avi", "webm" -> Icons.Rounded.Movie
        "mp3", "wav", "flac", "ogg", "m4a" -> Icons.Rounded.AudioFile
        "zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar", "zst" -> Icons.Rounded.FolderZip
        "txt", "md", "log", "conf", "cfg", "ini", "json", "yaml", "yml", "xml", "csv", "sh", "py", "c", "h",
        "cpp", "kt", "java", "js", "ts", "go", "rs", "toml" -> Icons.Rounded.Description
        "pem", "key", "pub", "crt" -> Icons.Rounded.Lock
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionsSheet(
    file: RemoteFile,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onChmod: () -> Unit,
    onCopyPath: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(file), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (!file.isDirectoryLike) formatSize(file.size) else null,
                            formatDate(file.modifiedEpochSeconds).ifEmpty { null },
                            formatPermissions(file.permissions).ifEmpty { null },
                        ).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (!file.isDirectoryLike) {
                SheetAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.sftp_open), onOpen)
                SheetAction(Icons.Rounded.Download, stringResource(R.string.sftp_download), onDownload)
            }
            SheetAction(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.sftp_rename), onRename)
            SheetAction(Icons.Rounded.Lock, stringResource(R.string.sftp_permissions), onChmod)
            SheetAction(Icons.Rounded.ContentCopy, stringResource(R.string.sftp_copy_path), onCopyPath)
            SheetAction(Icons.Rounded.Delete, stringResource(R.string.action_delete), onDelete, danger = true)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    allowSlash: Boolean = false,
) {
    var value by remember { mutableStateOf(initial) }
    val valid = value.isNotBlank() && (allowSlash || (!value.contains('/') && value != "." && value != ".."))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(value.trim()) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChmodDialog(file: RemoteFile, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var mode by remember { mutableStateOf(if (file.permissions >= 0) file.permissions and 0x1FF else 0x1A4) }
    val who = listOf(R.string.perm_owner, R.string.perm_group, R.string.perm_other)
    val what = listOf("r", "w", "x")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_permissions)) },
        text = {
            Column {
                Text(
                    "%03o  %s".format(mode, formatPermissions(mode)),
                    fontFamily = MonoFamily,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row {
                    Spacer(Modifier.width(72.dp))
                    what.forEach { Text(it, fontFamily = MonoFamily, modifier = Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                who.forEachIndexed { wi, label ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(stringResource(label), modifier = Modifier.width(72.dp))
                        what.indices.forEach { bi ->
                            val bit = 1 shl (8 - (wi * 3 + bi))
                            Box(Modifier.width(48.dp)) {
                                Checkbox(checked = mode and bit != 0, onCheckedChange = { on -> mode = if (on) mode or bit else mode and bit.inv() })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode) }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TransfersPanel(controller: SftpController) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sftp_transfers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.clearFinished() }) { Text(stringResource(R.string.sftp_clear_finished)) }
            }
            LazyColumn(Modifier.heightIn(max = 180.dp)) {
                items(controller.transfers, key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (t.status) {
                                TransferStatus.DONE -> Icons.Rounded.Done
                                TransferStatus.FAILED, TransferStatus.CANCELLED -> Icons.Rounded.ErrorOutline
                                TransferStatus.RUNNING -> if (t.upload) Icons.Rounded.Upload else Icons.Rounded.Download
                            },
                            null,
                            tint = when (t.status) {
                                TransferStatus.DONE -> MaterialTheme.colorScheme.secondary
                                TransferStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            when (t.status) {
                                TransferStatus.RUNNING -> {
                                    if (t.total > 0) {
                                        LinearProgressIndicator(
                                            progress = { (t.transferred.toFloat() / t.total).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        )
                                    } else {
                                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                    }
                                    Text(
                                        formatSize(t.transferred) + if (t.total > 0) " / " + formatSize(t.total) else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TransferStatus.DONE -> Text(stringResource(R.string.sftp_transfer_done), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TransferStatus.CANCELLED -> Text(stringResource(R.string.sftp_transfer_cancelled), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TransferStatus.FAILED -> Text(t.error ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                            }
                        }
                        IconButton(onClick = { if (t.status == TransferStatus.RUNNING) controller.cancel(t) else controller.dismiss(t) }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        }
    }
}
