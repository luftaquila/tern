// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.luftaquila.tern.AppContainer
import io.github.luftaquila.tern.ui.home.HomeScreen
import io.github.luftaquila.tern.ui.hosts.HostEditScreen
import io.github.luftaquila.tern.ui.settings.ExtraKeysEditorScreen
import io.github.luftaquila.tern.ui.settings.KnownHostsScreen
import io.github.luftaquila.tern.ui.settings.LicensesScreen
import io.github.luftaquila.tern.ui.sftp.SftpScreen
import io.github.luftaquila.tern.ui.terminal.TerminalScreen

sealed interface Route {
    data object Home : Route
    data class HostEditor(val hostId: String?) : Route
    data class Terminal(val sessionId: Long) : Route
    data class Sftp(val hostId: String, val sessionId: Long? = null) : Route
    data object KnownHosts : Route
    data object HostCas : Route
    data object Licenses : Route
    data object ExtraKeysEditor : Route
    data object Snippets : Route
    data object Fonts : Route
    data object Donate : Route
    data object AppLock : Route
    data class LockSetup(val type: io.github.luftaquila.tern.data.LockType, val enableBiometric: Boolean = false) : Route
}

/** Minimal back stack; the app has few screens and no deep links. */
@Stable
class Navigator {
    val backStack = mutableStateListOf<Route>(Route.Home)
    var lastWasPush by mutableStateOf(true)
        private set

    val current: Route get() = backStack.last()

    fun push(route: Route) {
        lastWasPush = true
        backStack.add(route)
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        lastWasPush = false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun popToHome() {
        if (backStack.size <= 1) return
        lastWasPush = false
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Replaces the top screen (e.g. host list → terminal after connecting from an editor). */
    fun replace(route: Route) {
        lastWasPush = true
        backStack[backStack.lastIndex] = route
    }

    /** Removes every screen showing [sessionId] (session closed). */
    fun dropSession(sessionId: Long) {
        val before = backStack.size
        backStack.removeAll { (it is Route.Terminal && it.sessionId == sessionId) || (it is Route.Sftp && it.sessionId == sessionId) }
        if (backStack.isEmpty()) backStack.add(Route.Home)
        if (backStack.size != before) lastWasPush = false
    }
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("no navigator") }

/** Asks for notification permission (Android 13+) the first time a session is opened. */
val LocalRequestNotificationPermission = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun TernNavHost(navigator: Navigator) {
    val stateHolder = rememberSaveableStateHolder()
    // A screen that left the back stack starts fresh when it is opened again (a new host form
    // must not show the host typed last time): drop the saved state of routes that are gone.
    val keys = navigator.backStack.map { it.toString() }.toSet()
    val shown = remember { mutableSetOf<String>() }
    LaunchedEffect(keys) {
        (shown - keys).forEach(stateHolder::removeState)
        shown.clear()
        shown.addAll(keys)
    }
    BackHandler(enabled = navigator.backStack.size > 1) { navigator.pop() }
    AnimatedContent(
        targetState = navigator.current,
        transitionSpec = {
            if (navigator.lastWasPush) {
                (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
            } else {
                fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(260)) { it / 5 } + fadeOut(tween(200)))
            }
        },
        label = "navigation",
    ) { route ->
        stateHolder.SaveableStateProvider(route.toString()) {
            when (route) {
                Route.Home -> HomeScreen()
                is Route.HostEditor -> HostEditScreen(route.hostId)
                is Route.Terminal -> TerminalScreen(route.sessionId)
                is Route.Sftp -> SftpScreen(route.hostId, route.sessionId)
                Route.KnownHosts -> KnownHostsScreen()
                Route.HostCas -> io.github.luftaquila.tern.ui.settings.HostCasScreen()
                Route.Licenses -> LicensesScreen()
                Route.ExtraKeysEditor -> ExtraKeysEditorScreen()
                Route.Snippets -> io.github.luftaquila.tern.ui.snippets.SnippetsScreen()
                Route.Fonts -> io.github.luftaquila.tern.ui.settings.FontScreen()
                Route.Donate -> io.github.luftaquila.tern.ui.settings.DonateScreen()
                Route.AppLock -> io.github.luftaquila.tern.ui.lock.AppLockScreen()
                is Route.LockSetup -> io.github.luftaquila.tern.ui.lock.LockSetupScreen(route.type, route.enableBiometric)
            }
        }
    }
}
