// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.data.ImportedFont
import io.github.luftaquila.tern.data.TerminalFont
import io.github.luftaquila.tern.input.StickyModifiers
import io.github.luftaquila.tern.terminal.ColorSchemes
import io.github.luftaquila.tern.termview.PreviewBackend
import io.github.luftaquila.tern.termview.TerminalFonts
import io.github.luftaquila.tern.termview.TerminalView
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Bundled fonts in list order; [TerminalFont.SYSTEM_MONO] last. */
private val BUNDLED_FONTS = TerminalFont.entries.filter { it != TerminalFont.CUSTOM }

/** A font choice: a bundled font, or an imported one ([TerminalFont.CUSTOM] with its file). */
private data class FontChoice(val font: TerminalFont, val file: String? = null)

/** Compose font family that draws [font] (with [file] when imported) the way the terminal does. */
fun fontFamilyFor(context: Context, font: TerminalFont, file: String?): FontFamily =
    TerminalFonts.typeface(context, font, file)?.let { FontFamily(it) } ?: FontFamily.Monospace

@Composable
fun fontLabel(font: TerminalFont, file: String?, settings: AppSettings): String = when (font) {
    TerminalFont.JETENDARD -> "Jetendard"
    TerminalFont.D2CODING -> "D2Coding"
    TerminalFont.JETBRAINS_MONO -> "JetBrains Mono"
    TerminalFont.FIRA_CODE -> "Fira Code"
    TerminalFont.CASCADIA_CODE -> "Cascadia Code"
    TerminalFont.SOURCE_CODE_PRO -> "Source Code Pro"
    TerminalFont.IBM_PLEX_MONO -> "IBM Plex Mono"
    TerminalFont.HACK -> "Hack"
    TerminalFont.MESLO -> "Meslo LG S"
    TerminalFont.IOSEVKA -> "Iosevka"
    TerminalFont.ZERO_X_PROTO -> "0xProto"
    TerminalFont.SYSTEM_MONO -> stringResource(R.string.font_system)
    TerminalFont.CUSTOM -> settings.importedFonts.firstOrNull { it.file == file }?.name ?: stringResource(R.string.font_imported)
}

@Composable
private fun fontDescription(font: TerminalFont): String = when (font) {
    TerminalFont.JETENDARD -> stringResource(R.string.font_desc_jetendard)
    TerminalFont.D2CODING -> stringResource(R.string.font_desc_d2coding)
    TerminalFont.JETBRAINS_MONO -> stringResource(R.string.font_desc_jetbrains)
    TerminalFont.FIRA_CODE -> stringResource(R.string.font_desc_fira)
    TerminalFont.CASCADIA_CODE -> stringResource(R.string.font_desc_cascadia)
    TerminalFont.SOURCE_CODE_PRO -> "Adobe"
    TerminalFont.IBM_PLEX_MONO -> "IBM"
    TerminalFont.HACK -> "Source Foundry"
    TerminalFont.MESLO -> stringResource(R.string.font_desc_meslo)
    TerminalFont.IOSEVKA -> stringResource(R.string.font_desc_iosevka)
    TerminalFont.ZERO_X_PROTO -> stringResource(R.string.font_desc_0xproto)
    TerminalFont.SYSTEM_MONO -> stringResource(R.string.font_desc_system)
    TerminalFont.CUSTOM -> stringResource(R.string.font_imported)
}

/** Settings row: the current font in its own face and the text size; opens [FontScreen]. */
@Composable
fun FontRow(settings: AppSettings, onClick: () -> Unit) {
    val context = LocalContext.current
    val family = remember(settings.font, settings.customFontFile) { fontFamilyFor(context, settings.font, settings.customFontFile) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(fontLabel(settings.font, settings.customFontFile, settings), fontFamily = family, fontSize = 17.sp, maxLines = 1)
            Text(
                "${settings.fontSizeSp.roundToInt()}sp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Font settings: a live terminal preview (the real renderer, so the fallback font and ligatures
 * show exactly as in sessions), the text size, the font list with imported fonts, the fallback
 * font and ligatures. Choosing applies at once; the screen stays open for comparing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    fun update(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)
    var size by remember(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp) }
    var fallbackSheet by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ImportedFont?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (file, name) = withContext(Dispatchers.IO) { TerminalFonts.import(context, uri) }
                update { it.copy(importedFonts = it.importedFonts + ImportedFont(file, name), font = TerminalFont.CUSTOM, customFontFile = file) }
                Toast.makeText(context, context.getString(R.string.font_imported_toast, name), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val message = (e as? TerminalFonts.ImportException)?.message ?: context.getString(R.string.font_import_failed)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_font)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // The size control sits above the preview, which grows with the size: dragging must
            // not move the slider under the finger.
            Row(Modifier.padding(start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_font_size), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${size.roundToInt()}sp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = size,
                onValueChange = { size = it.roundToInt().toFloat() },
                onValueChangeFinished = { update { it.copy(fontSizeSp = size) } },
                valueRange = AppSettings.MIN_FONT_SP..AppSettings.MAX_FONT_SP,
                steps = (AppSettings.MAX_FONT_SP - AppSettings.MIN_FONT_SP).toInt() - 1,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FontPreview(settings, size, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                item { SectionLabel(stringResource(R.string.font_section_fonts)) }
                items(BUNDLED_FONTS, key = { it.name }) { font ->
                    FontItem(FontChoice(font), settings, selected = settings.font == font) {
                        update { it.copy(font = font, customFontFile = null) }
                    }
                }
                items(settings.importedFonts, key = { it.file }) { imported ->
                    val choice = FontChoice(TerminalFont.CUSTOM, imported.file)
                    FontItem(
                        choice,
                        settings,
                        selected = settings.font == TerminalFont.CUSTOM && settings.customFontFile == imported.file,
                        onDelete = { deleting = imported },
                    ) { update { it.copy(font = TerminalFont.CUSTOM, customFontFile = imported.file) } }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                container.lock.beforeExternalPicker()
                                picker.launch(arrayOf("*/*"))
                            }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.FileOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.font_import_new), color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.font_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SectionLabel(stringResource(R.string.font_section_rendering)) }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { fallbackSheet = true }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.font_fallback), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                fallbackLabel(settings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { update { it.copy(ligatures = !it.ligatures) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.font_ligatures), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.font_ligatures_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = settings.ligatures, onCheckedChange = { v -> update { it.copy(ligatures = v) } })
                    }
                }
                item {
                    Text(
                        stringResource(R.string.font_nerd_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    if (fallbackSheet) {
        FallbackSheet(settings, onSelect = { choice ->
            update { it.copy(fallbackFont = choice.font, fallbackFontFile = choice.file) }
        }, onDismiss = { fallbackSheet = false })
    }

    deleting?.let { font ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.font_delete_title)) },
            text = { Text(stringResource(R.string.font_delete_message, font.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    update { s ->
                        s.copy(
                            importedFonts = s.importedFonts.filterNot { it.file == font.file },
                            font = if (s.font == TerminalFont.CUSTOM && s.customFontFile == font.file) TerminalFont.JETBRAINS_MONO else s.font,
                            customFontFile = s.customFontFile.takeUnless { it == font.file },
                            fallbackFont = if (s.fallbackFont == TerminalFont.CUSTOM && s.fallbackFontFile == font.file) TerminalFont.JETENDARD else s.fallbackFont,
                            fallbackFontFile = s.fallbackFontFile.takeUnless { it == font.file },
                        )
                    }
                    scope.launch(Dispatchers.IO) { TerminalFonts.delete(context, font.file) }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun fallbackLabel(settings: AppSettings): String =
    if (settings.fallbackFont == TerminalFont.SYSTEM_MONO) {
        stringResource(R.string.font_fallback_system)
    } else {
        fontLabel(settings.fallbackFont, settings.fallbackFontFile, settings)
    }

/**
 * The live preview: a [TerminalView] showing [PREVIEW] with the chosen fonts and [sizeSp]. It is as
 * tall as the rows of the tallest font in the list, so choosing another font never changes its
 * height (shorter fonts leave some space below the text).
 */
@Composable
private fun FontPreview(settings: AppSettings, sizeSp: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scheme = remember(settings.colorSchemeId) { ColorSchemes.byId(settings.colorSchemeId) }
    val backend = remember(scheme) { PreviewBackend(scheme, PREVIEW) }
    val typeface = remember(settings.font, settings.customFontFile) { TerminalFonts.primary(context, settings) }
    val fallback = remember(settings.fallbackFont, settings.fallbackFontFile) { TerminalFonts.fallback(context, settings) }
    val choices = remember(settings.importedFonts) {
        BUNDLED_FONTS.mapNotNull { TerminalFonts.typeface(context, it, null) } +
            settings.importedFonts.mapNotNull { TerminalFonts.typeface(context, TerminalFont.CUSTOM, it.file) }
    }
    val heightPx = remember(sizeSp, choices, typeface) {
        (choices + typeface).maxOf { TerminalView.heightForRows(context, it, sizeSp, PREVIEW_ROWS) }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = Color(scheme.background), modifier = modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 10.dp).height(with(density) { heightPx.toDp() })) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, StickyModifiers()).apply {
                        // A picture, not a terminal: no focus, keyboard or selection.
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                },
                update = { v ->
                    v.configureFont(typeface, fallback, sizeSp, settings.ligatures)
                    v.backend = backend
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Swallows touches so the preview cannot be scrolled, selected or focused.
            Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        }
    }
}

/**
 * Preview rows: a prompt, ligature candidates, look-alike glyphs, icons and box drawing, and
 * several scripts (drawn by the fallback and system fonts when the font lacks them). Each row
 * fits 22 columns, the width at the largest text size, so nothing wraps.
 */
private val PREVIEW: String = run {
    val esc = "\u001b"
    listOf(
        "$esc[?25l$esc[1;32muser@host$esc[0m:$esc[1;34m~$esc[0m$ git log",
        "if a != b && x >= 0",
        "0O 1lI| {} -> => <=",
        "\uE0A0 main \uF07B src \u2713 \u256D\u2500\u256E\u2583\u2585\u2587",
        "한글 日本語 中文",
        "Ελληνικά Русский",
    ).joinToString("\r\n")
}
private const val PREVIEW_ROWS = 6

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A font in the list: its name in its own face and a short description. */
@Composable
private fun FontItem(
    choice: FontChoice,
    settings: AppSettings,
    selected: Boolean,
    label: String? = null,
    description: String? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val family = remember(choice) { fontFamilyFor(context, choice.font, choice.file) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(
                    label ?: fontLabel(choice.font, choice.file, settings),
                    fontFamily = family,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(description ?: fontDescription(choice.font), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.font_delete)) }
            }
        }
    }
}

/** Picks the fallback font: any bundled or imported font, or the system fonts only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FallbackSheet(settings: AppSettings, onSelect: (FontChoice) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.font_fallback), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
            Text(
                stringResource(R.string.font_fallback_sheet_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            )
            val choices = BUNDLED_FONTS.filter { it != TerminalFont.SYSTEM_MONO }.map { FontChoice(it) } +
                settings.importedFonts.map { FontChoice(TerminalFont.CUSTOM, it.file) }
            choices.forEach { choice ->
                FontItem(choice, settings, selected = settings.fallbackFont == choice.font && settings.fallbackFontFile == choice.file) {
                    onSelect(choice)
                    onDismiss()
                }
            }
            FontItem(
                FontChoice(TerminalFont.SYSTEM_MONO),
                settings,
                selected = settings.fallbackFont == TerminalFont.SYSTEM_MONO,
                label = stringResource(R.string.font_fallback_system),
                description = stringResource(R.string.font_fallback_system_desc),
            ) {
                onSelect(FontChoice(TerminalFont.SYSTEM_MONO))
                onDismiss()
            }
        }
    }
}
