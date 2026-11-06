// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.home

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.ui.hosts.HostListTab
import io.github.luftaquila.tern.ui.keys.KeysTab
import io.github.luftaquila.tern.ui.settings.SettingsTab

private enum class HomeTab(val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOSTS(R.string.tab_hosts, Icons.Outlined.Dns, Icons.Rounded.Dns),
    KEYS(R.string.tab_keys, Icons.Outlined.Key, Icons.Rounded.Key),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@Composable
fun HomeScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                HomeTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(if (tab == t.ordinal) t.selectedIcon else t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (HomeTab.entries[tab]) {
            HomeTab.HOSTS -> HostListTab(modifier)
            HomeTab.KEYS -> KeysTab(modifier)
            HomeTab.SETTINGS -> SettingsTab(modifier)
        }
    }
}
