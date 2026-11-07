// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.terminal

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.input.StickyModifiers
import io.github.luftaquila.tern.session.SessionState
import io.github.luftaquila.tern.session.TerminalSession
import io.github.luftaquila.tern.terminal.ColorSchemes
import io.github.luftaquila.tern.terminal.TerminalKey
import io.github.luftaquila.tern.termview.TerminalFonts
import io.github.luftaquila.tern.termview.TerminalView
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.Route
import io.github.luftaquila.tern.ui.theme.MonoFamily
import kotlinx.coroutines.delay

@Composable
fun TerminalScreen(sessionId: Long) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle()
    val session = sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        LaunchedEffect(sessionId) { navigator.dropSession(sessionId) }
        return
    }
    TerminalContent(session, sessions)
}

@Composable
private fun TerminalContent(session: TerminalSession, sessions: List<TerminalSession>) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val title by session.title.collectAsStateWithLifecycle()
    val prompt by session.prompts.pending.collectAsStateWithLifecycle()
    val modifiers = remember { StickyModifiers() }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    val snippets by container.snippets.snippets.collectAsStateWithLifecycle()
    val snippetsById = remember(snippets) { snippets.associateBy { it.id } }
    var showSnippets by remember { mutableStateOf(false) }
    BackHandler(enabled = showSnippets) { showSnippets = false }
    val actions = remember {
        object : ExtraKeyActions {
            override fun onKey(key: TerminalKey) { terminalView?.sendKey(key) }
            override fun onText(text: String) { terminalView?.sendTypedText(text) }
            override fun onRaw(text: String) { terminalView?.sendRaw(text) }
            override fun onCtrl(letter: Char) { terminalView?.sendCtrl(letter) }
            override fun onPaste() { terminalView?.pasteFromClipboard() }
            override fun onToggleKeyboard() { terminalView?.toggleKeyboard() }
            override fun onSnippets() { showSnippets = !showSnippets }
            override fun onModifierArmed() { terminalView?.commitImeComposition() }
        }
    }

    /** Opens a non-terminal screen: finish the IME composition and put the keyboard away first. */
    fun leaveTerminal(route: Route) {
        terminalView?.let { v ->
            v.commitImeComposition()
            v.hideKeyboard()
        }
        navigator.push(route)
    }

    val scheme = ColorSchemes.byId(settings.colorSchemeId)
    val termBg = Color(scheme.background)
    val termFg = Color(scheme.foreground)
    val typeface = remember(settings.font, settings.customFontFile) { TerminalFonts.primary(context, settings) }
    val fallback = remember(settings.fallbackFont, settings.fallbackFontFile) { TerminalFonts.fallback(context, settings) }

    // Keep the screen awake and match the status bar icons to the terminal colors.
    val hostView = LocalView.current
    DisposableEffect(settings.keepScreenOn, scheme.isDark) {
        hostView.keepScreenOn = settings.keepScreenOn
        val window = (hostView.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, hostView) }
        val oldLightStatus = controller?.isAppearanceLightStatusBars
        val oldLightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !scheme.isDark
        controller?.isAppearanceLightNavigationBars = !scheme.isDark
        onDispose {
            hostView.keepScreenOn = false
            if (oldLightStatus != null) controller.isAppearanceLightStatusBars = oldLightStatus
            if (oldLightNav != null) controller.isAppearanceLightNavigationBars = oldLightNav
        }
    }

    LaunchedEffect(terminalView, session.id) {
        delay(250)
        terminalView?.showKeyboard()
    }
    LaunchedEffect(state) {
        if (state == SessionState.Connected) terminalView?.showKeyboard()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(termBg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TerminalTopBar(
            session = session,
            sessions = sessions,
            title = title,
            state = state,
            fg = termFg,
            settings = settings,
            onBack = { navigator.pop() },
            onSwitch = { navigator.replace(Route.Terminal(it.id)) },
            onNewConnection = { navigator.popToHome() },
            onSftp = { leaveTerminal(Route.Sftp(session.host.id, session.id)) },
            onPaste = { terminalView?.pasteFromClipboard() },
            onFontSize = { delta ->
                val size = (settings.fontSizeSp + delta).coerceIn(AppSettings.MIN_FONT_SP, AppSettings.MAX_FONT_SP)
                container.settings.update { it.copy(fontSizeSp = size) }
            },
            onToggleExtraKeys = { container.settings.update { it.copy(showExtraKeys = !it.showExtraKeys) } },
            onEditExtraKeys = { leaveTerminal(Route.ExtraKeysEditor) },
            onSnippets = { showSnippets = true },
            onReconnect = { session.reconnect() },
            onClose = {
                container.sessions.close(session.id)
                navigator.dropSession(session.id)
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, modifiers).also { v ->
                        v.onFontSizeChanged = { sp -> container.settings.update { it.copy(fontSizeSp = sp) } }
                        terminalView = v
                    }
                },
                update = { v ->
                    v.backspaceSendsCtrlH = settings.backspaceSendsCtrlH
                    v.scrollSpeed = settings.scrollSpeed
                    v.imeIncognito = settings.imeIncognito
                    v.cursorBlink = settings.cursorBlink
                    v.configureFont(typeface, fallback, settings.fontSizeSp, settings.ligatures)
                    v.backend = session
                },
                onRelease = { v ->
                    v.backend = null
                    if (terminalView === v) terminalView = null
                },
                modifier = Modifier.fillMaxSize().clipToBounds(),
            )
            if (state == SessionState.Connecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter))
            }
            val ended = state as? SessionState.Disconnected
            if (ended != null) {
                DisconnectedBanner(
                    message = ended.message,
                    onReconnect = { session.reconnect() },
                    onClose = {
                        container.sessions.close(session.id)
                        navigator.dropSession(session.id)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (showSnippets) {
                SnippetPanel(
                    snippets = snippets,
                    onSend = { snippet ->
                        showSnippets = false
                        actions.sendText(snippet.text)
                    },
                    onManage = {
                        showSnippets = false
                        leaveTerminal(Route.Snippets)
                    },
                    onClose = { showSnippets = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        if (settings.showExtraKeys) {
            ExtraKeysBar(
                config = settings.extraKeys,
                snippets = snippetsById,
                modifiers = modifiers,
                expanded = settings.extraKeysExpanded,
                onExpandedChange = { open -> container.settings.update { it.copy(extraKeysExpanded = open) } },
                background = termBg,
                actions = actions,
                scale = settings.extraKeysScale,
                onEdit = { leaveTerminal(Route.ExtraKeysEditor) },
            )
        }
    }
    PromptDialog(prompt)
}

@Composable
private fun TerminalTopBar(
    session: TerminalSession,
    sessions: List<TerminalSession>,
    title: String,
    state: SessionState,
    fg: Color,
    settings: AppSettings,
    onBack: () -> Unit,
    onSwitch: (TerminalSession) -> Unit,
    onNewConnection: () -> Unit,
    onSftp: () -> Unit,
    onPaste: () -> Unit,
    onFontSize: (Float) -> Unit,
    onToggleExtraKeys: () -> Unit,
    onEditExtraKeys: () -> Unit,
    onSnippets: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
) {
    var switcher by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val dim = fg.copy(alpha = 0.6f)
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = fg) }
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { switcher = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(title, color = fg, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(state)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        session.host.address + if (sessions.size > 1) " (${sessions.indexOf(session) + 1}/${sessions.size})" else "",
                        color = dim,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(expanded = switcher, onDismissRequest = { switcher = false }) {
                sessions.forEach { s ->
                    key(s.id) {
                    val sTitle by s.title.collectAsStateWithLifecycle()
                    val sState by s.state.collectAsStateWithLifecycle()
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(sTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.host.address, style = MaterialTheme.typography.labelSmall, fontFamily = MonoFamily)
                            }
                        },
                        leadingIcon = { Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) { StatusDot(sState) } },
                        trailingIcon = { if (s.id == session.id) Icon(Icons.Rounded.Terminal, null) },
                        onClick = {
                            switcher = false
                            if (s.id != session.id) onSwitch(s)
                        },
                    )
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_new_connection)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, null) },
                    onClick = { switcher = false; onNewConnection() },
                )
            }
        }
        IconButton(onClick = onSftp) { Icon(Icons.Rounded.FolderOpen, stringResource(R.string.action_sftp), tint = fg) }
        IconButton(onClick = onPaste) { Icon(Icons.Rounded.ContentPaste, stringResource(R.string.action_paste), tint = fg) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more), tint = fg) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_font_bigger)) },
                    leadingIcon = { Icon(Icons.Rounded.TextIncrease, null) },
                    onClick = { onFontSize(1f) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_font_smaller)) },
                    leadingIcon = { Icon(Icons.Rounded.TextDecrease, null) },
                    onClick = { onFontSize(-1f) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (settings.showExtraKeys) R.string.action_hide_extra_keys else R.string.action_show_extra_keys)) },
                    leadingIcon = { Icon(Icons.Rounded.KeyboardHide, null) },
                    onClick = { menu = false; onToggleExtraKeys() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit_extra_keys)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onEditExtraKeys() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.snippets_title)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null) },
                    onClick = { menu = false; onSnippets() },
                )
                if (state is SessionState.Disconnected) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reconnect)) },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                        onClick = { menu = false; onReconnect() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_close_session), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onClose() },
                )
            }
        }
    }
}

@Composable
private fun StatusDot(state: SessionState) {
    val color = when (state) {
        SessionState.Connected -> MaterialTheme.colorScheme.secondary
        SessionState.Connecting -> MaterialTheme.colorScheme.tertiary
        is SessionState.Disconnected -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
private fun DisconnectedBanner(message: String?, onReconnect: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        modifier = modifier.padding(12.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LinkOff, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.status_disconnected), style = MaterialTheme.typography.titleSmall)
                if (!message.isNullOrBlank()) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            TextButton(onClick = onReconnect) { Text(stringResource(R.string.action_reconnect)) }
        }
    }
}
