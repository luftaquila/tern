// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AuthMethod
import io.github.luftaquila.tern.data.Host
import io.github.luftaquila.tern.data.SecretBox
import io.github.luftaquila.tern.data.groupNames
import io.github.luftaquila.tern.data.tagNames
import io.github.luftaquila.tern.session.HostConnector
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.theme.HostColors
import io.github.luftaquila.tern.ui.theme.MonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(hostId: String?) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val original = remember(hostId) { hostId?.let { container.hosts.get(it) } }
    val keys by container.keys.keys.collectAsStateWithLifecycle()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()

    var label by rememberSaveable { mutableStateOf(original?.label ?: "") }
    var hostname by rememberSaveable { mutableStateOf(original?.hostname ?: "") }
    var port by rememberSaveable { mutableStateOf((original?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(original?.username ?: "") }
    var authMethod by rememberSaveable { mutableStateOf(original?.authMethod ?: AuthMethod.PASSWORD) }
    var password by rememberSaveable { mutableStateOf("") }
    var hasSavedPassword by rememberSaveable { mutableStateOf(original?.encryptedPassword != null) }
    var showPassword by remember { mutableStateOf(false) }
    var keyId by rememberSaveable { mutableStateOf(original?.keyId) }
    var colorIndex by rememberSaveable { mutableIntStateOf(original?.colorIndex ?: (0 until HostColors.size).random()) }
    var group by rememberSaveable { mutableStateOf(original?.group ?: "") }
    var tags by rememberSaveable(stateSaver = TagsSaver) { mutableStateOf(original?.tags ?: emptyList()) }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf(original?.lang ?: Host.DEFAULT_LANG) }
    var startup by rememberSaveable { mutableStateOf(original?.startupCommand ?: "") }
    var termType by rememberSaveable { mutableStateOf(original?.termType ?: Host.DEFAULT_TERM) }
    var keepAlive by rememberSaveable { mutableStateOf((original?.keepAliveSeconds ?: 30).toString()) }
    var jumpHostId by rememberSaveable { mutableStateOf(original?.jumpHostId) }
    var forwards by rememberSaveable(stateSaver = ForwardsSaver) { mutableStateOf(original?.forwards ?: emptyList()) }
    var agentForwarding by rememberSaveable { mutableStateOf(original?.agentForwarding ?: false) }
    var showErrors by remember { mutableStateOf(false) }

    // Hosts that do not already go through this one (that would loop).
    val jumpCandidates = remember(hosts, original) {
        hosts.filter { candidate ->
            candidate.id != original?.id && HostConnector.jumpHosts(container.hosts, candidate).none { it.id == original?.id }
        }
    }

    val knownGroups = remember(hosts) { hosts.groupNames() }
    val knownTags = remember(hosts) { hosts.tagNames() }

    val portValue = port.toIntOrNull()
    val hostError = hostname.isBlank()
    val userError = username.isBlank()
    val portError = portValue == null || portValue !in 1..65535
    val keyError = authMethod == AuthMethod.KEY && keys.none { it.id == keyId }

    fun save() {
        if (hostError || userError || portError || keyError) {
            showErrors = true
            return
        }
        val encrypted = when {
            password.isNotEmpty() -> SecretBox.encrypt(password)
            hasSavedPassword -> original?.encryptedPassword
            else -> null
        }
        val host = (original ?: Host()).copy(
            label = label.trim(),
            hostname = hostname.trim(),
            port = portValue!!,
            username = username.trim(),
            authMethod = authMethod,
            encryptedPassword = encrypted,
            keyId = if (authMethod == AuthMethod.KEY) keyId else original?.keyId,
            colorIndex = colorIndex,
            // An existing group spelled with other capitals is the same group.
            group = Host.groupOf(group).let { g -> knownGroups.firstOrNull { it.equals(g, ignoreCase = true) } ?: g },
            // A tag still in the field counts too.
            tags = withTags(tags, tagInput, knownTags),
            lang = lang.trim(),
            startupCommand = startup,
            termType = termType.trim().ifEmpty { Host.DEFAULT_TERM },
            keepAliveSeconds = keepAlive.toIntOrNull()?.coerceIn(0, 3600) ?: 30,
            jumpHostId = jumpHostId?.takeIf { id -> jumpCandidates.any { it.id == id } },
            forwards = forwards,
            agentForwarding = agentForwarding,
        )
        container.hosts.upsert(host)
        navigator.pop()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (original == null) R.string.title_new_host else R.string.title_edit_host)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = ::save) { Text(stringResource(R.string.action_save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FormSection(stringResource(R.string.section_connection)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text(stringResource(R.string.field_label)) },
                    placeholder = { Text(stringResource(R.string.field_label_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hostname, onValueChange = { hostname = it.trim() },
                        label = { Text(stringResource(R.string.field_hostname)) },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        isError = showErrors && hostError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.field_port)) },
                        singleLine = true,
                        isError = showErrors && portError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                }
                OutlinedTextField(
                    value = username, onValueChange = { username = it.trim() },
                    label = { Text(stringResource(R.string.field_username)) },
                    singleLine = true,
                    isError = showErrors && userError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            FormSection(stringResource(R.string.section_auth)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(AuthMethod.PASSWORD to R.string.auth_password, AuthMethod.KEY to R.string.auth_key)
                    options.forEachIndexed { i, (method, text) ->
                        SegmentedButton(
                            selected = authMethod == method,
                            onClick = { authMethod = method },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(stringResource(text)) }
                    }
                }
                if (authMethod == AuthMethod.KEY) {
                    var expanded by remember { mutableStateOf(false) }
                    val selected = keys.firstOrNull { it.id == keyId }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && keys.isNotEmpty() }) {
                        OutlinedTextField(
                            value = selected?.let { "${it.name} (${it.keyType})" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.field_key)) },
                            placeholder = { Text(stringResource(if (keys.isEmpty()) R.string.no_keys_hint else R.string.select_key)) },
                            isError = showErrors && keyError,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            keys.forEach { k ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(k.name)
                                            Text(k.fingerprint, style = MaterialTheme.typography.labelSmall, fontFamily = MonoFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { keyId = k.id; expanded = false },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.field_password)) },
                    placeholder = {
                        Text(stringResource(if (hasSavedPassword) R.string.password_saved_hint else R.string.password_ask_hint))
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                        }
                    },
                    supportingText = {
                        Text(stringResource(if (authMethod == AuthMethod.KEY) R.string.password_key_fallback else R.string.password_stored_securely))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasSavedPassword && password.isEmpty()) {
                    TextButton(onClick = { hasSavedPassword = false }) {
                        Text(stringResource(R.string.action_forget_password), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            FormSection(stringResource(R.string.section_appearance)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HostColors.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(
                                    if (i == colorIndex) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                                )
                                .clickable { colorIndex = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == colorIndex) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            GroupSection(
                group = group,
                onGroupChange = { group = it },
                tags = tags,
                onTagsChange = { tags = it },
                tagInput = tagInput,
                onTagInputChange = { tagInput = it },
                knownGroups = knownGroups,
                knownTags = knownTags,
            )

            FormSection(stringResource(R.string.section_advanced)) {
                LangField(lang) { lang = it }
                OutlinedTextField(
                    value = startup, onValueChange = { startup = it },
                    label = { Text(stringResource(R.string.field_startup_command)) },
                    placeholder = { Text("tmux new -A -s main") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = termType, onValueChange = { termType = it.trim() },
                        label = { Text(stringResource(R.string.field_term)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = keepAlive, onValueChange = { v -> keepAlive = v.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.field_keepalive)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(128.dp),
                    )
                }
            }

            JumpHostSection(
                selected = jumpCandidates.firstOrNull { it.id == jumpHostId },
                candidates = jumpCandidates,
                onSelect = { jumpHostId = it?.id },
            )
            ForwardsSection(forwards, onChange = { forwards = it })
            AgentSection(agentForwarding, onChange = { agentForwarding = it })

            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.action_save))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LangField(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.lang_none)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.field_lang)) },
            placeholder = { Text(noneLabel) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.lang_hint)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Host.LANG_PRESETS.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.ifEmpty { noneLabel }, fontFamily = if (preset.isEmpty()) null else MonoFamily) },
                    onClick = { onChange(preset); expanded = false },
                )
            }
        }
    }
}

@Composable
fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
