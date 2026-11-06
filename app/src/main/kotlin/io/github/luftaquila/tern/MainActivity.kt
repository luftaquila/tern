// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.LocalRequestNotificationPermission
import io.github.luftaquila.tern.ui.TernNavHost
import io.github.luftaquila.tern.ui.lock.LockScreen
import io.github.luftaquila.tern.ui.Navigator
import io.github.luftaquila.tern.ui.theme.TernTheme
import io.github.luftaquila.tern.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    private var askedNotifications = false
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as TernApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val navigator = remember { Navigator() }
            // System bar icons must follow the app's theme, which may differ from the system's.
            val dark = isDarkTheme(settings.themeMode)
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CompositionLocalProvider(
                LocalContainer provides container,
                LocalNavigator provides navigator,
                LocalRequestNotificationPermission provides ::requestNotificationPermission,
            ) {
                TernTheme(settings.themeMode, settings.dynamicColor) {
                    Box(Modifier.fillMaxSize()) {
                        TernNavHost(navigator)
                        val locked by container.lock.locked.collectAsStateWithLifecycle()
                        if (locked) LockScreen(container.lock, settings)
                    }
                }
                // With an app lock, the recents screen shows no snapshot of the terminal.
                val lockEnabled = container.lock.isEnabled(settings)
                LaunchedEffect(lockEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!lockEnabled)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as TernApp).container.lock.onStart()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as TernApp).container.lock.onStop()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedNotifications) return
        askedNotifications = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
