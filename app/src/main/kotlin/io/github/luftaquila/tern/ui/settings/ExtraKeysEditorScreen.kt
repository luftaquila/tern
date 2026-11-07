// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.settings

import android.app.Activity
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.data.ExtraKeyDef
import io.github.luftaquila.tern.data.ExtraKeysConfig
import io.github.luftaquila.tern.data.Snippet
import io.github.luftaquila.tern.data.SpecialKey
import io.github.luftaquila.tern.terminal.ColorSchemes
import io.github.luftaquila.tern.ui.LocalContainer
import io.github.luftaquila.tern.ui.LocalNavigator
import io.github.luftaquila.tern.ui.terminal.ExtraKeysBarLayout
import io.github.luftaquila.tern.ui.terminal.KeyCap
import io.github.luftaquila.tern.ui.terminal.KeyMetrics
import io.github.luftaquila.tern.ui.terminal.LocalKeyMetrics
import io.github.luftaquila.tern.ui.terminal.icon
import io.github.luftaquila.tern.ui.snippets.SnippetDialog
import io.github.luftaquila.tern.ui.terminal.label
import kotlin.math.roundToInt

/** A key in the editor. [id] tells duplicates (two "/" keys) apart while dragging. */
private class EditorKey(val id: Long, val def: ExtraKeyDef)

private typealias EditorRows = List<List<EditorKey>>

private const val MAX_UNDO = 50

private fun LayoutCoordinates.rootRect() = Rect(positionInRoot(), size.toSize())

/**
 * Editor model: the rows, the row palette taps add to, undo history and drag state.
 * Geometry is in root coordinates. A drag reorders [rows] live; the undo entry and the save
 * happen once, when the drag ends.
 */
@Stable
private class KeysEditorState(
    initial: List<List<ExtraKeyDef>>,
    private val onChange: (List<List<ExtraKeyDef>>) -> Unit,
) {
    private var nextId = 0L
    var rows by mutableStateOf(initial.map { row -> row.map(::newKey) })
        private set
    var selectedRow by mutableIntStateOf(0)
    private val history = ArrayList<EditorRows>()
    var canUndo by mutableStateOf(false)
        private set

    /** The key whose menu (edit/delete) is open. */
    var menuKey by mutableStateOf<Long?>(null)

    /** Layout of the bar preview, where the gestures are handled. */
    var preview: LayoutCoordinates? = null

    /** Dropping a dragged key here deletes it (the palette). */
    var deleteZone = Rect.Zero
    private val keyBounds = HashMap<Long, Rect>()

    /** The on-screen part of each row. */
    private val rowViewports = HashMap<Int, Rect>()
    private val rowScrolls = ArrayList<ScrollState>()

    /** A key just added from the palette, and where it landed once laid out (to scroll to it). */
    private var revealKey: Long? = null
    var revealRequest by mutableStateOf<Pair<Int, Rect>?>(null)

    /** How far the finger must travel after a move before the next one (prevents ping-pong). */
    var moveSlop = 0f

    var dragging by mutableStateOf<EditorKey?>(null)
        private set

    /** Finger position while dragging. */
    var pointer by mutableStateOf(Offset.Zero)
        private set

    /** Where in the key the finger grabbed it, and the key's size. */
    var grab = Offset.Zero
        private set
    var dragSize = Size.Zero
        private set
    private var beforeDrag: EditorRows? = null
    private var lastMoveAt: Offset? = null

    val overDelete: Boolean get() = dragging != null && deleteZone.contains(pointer)

    private fun newKey(def: ExtraKeyDef) = EditorKey(nextId++, def)

    private fun defs() = rows.map { row -> row.map { it.def } }

    private fun EditorRows.ids() = map { row -> row.map { it.id } }

    private fun pushHistory(snapshot: EditorRows) {
        history.add(snapshot)
        if (history.size > MAX_UNDO) history.removeAt(0)
        canUndo = true
    }

    private fun commit(newRows: EditorRows) {
        pushHistory(rows)
        rows = newRows
        clampSelection()
        onChange(defs())
    }

    private fun clampSelection() {
        selectedRow = selectedRow.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    }

    fun undo() {
        if (dragging != null || history.isEmpty()) return
        rows = history.removeAt(history.lastIndex)
        canUndo = history.isNotEmpty()
        clampSelection()
        onChange(defs())
    }

    fun add(def: ExtraKeyDef) {
        val key = newKey(def)
        commit(
            if (rows.isEmpty()) listOf(listOf(key)) else rows.mapIndexed { i, row -> if (i == selectedRow) row + key else row },
        )
        revealKey = key.id
    }

    fun remove(id: Long) {
        if (dragging != null) return
        commit(rows.map { row -> row.filterNot { it.id == id } })
    }

    fun replace(id: Long, def: ExtraKeyDef) = commit(rows.map { row -> row.map { if (it.id == id) EditorKey(id, def) else it } })

    fun addRow() {
        commit(rows + listOf(emptyList()))
        selectedRow = rows.lastIndex
    }

    fun deleteRow(index: Int) {
        if (rows.size <= 1 || index !in rows.indices) return
        commit(rows.filterIndexed { i, _ -> i != index })
    }

    fun moveRow(index: Int, delta: Int) {
        val target = index + delta
        if (index !in rows.indices || target !in rows.indices) return
        val m = rows.toMutableList()
        m.add(target, m.removeAt(index))
        commit(m)
        selectedRow = target
    }

    fun reset(defaults: List<List<ExtraKeyDef>>) {
        commit(defaults.map { row -> row.map(::newKey) })
        selectedRow = 0
    }

    // ---- geometry ----------------------------------------------------------------------

    fun rowScroll(index: Int): ScrollState {
        while (rowScrolls.size <= index) rowScrolls.add(ScrollState(0))
        return rowScrolls[index]
    }

    fun rowViewport(index: Int): Rect? = rowViewports[index]

    fun onKeyPlaced(id: Long, bounds: Rect) {
        keyBounds[id] = bounds
        if (revealKey == id) {
            revealKey = null
            locate(id)?.let { (r, _) -> revealRequest = r to bounds }
        }
    }

    fun onRowPlaced(index: Int, bounds: Rect) {
        rowViewports[index] = bounds
    }

    fun toRoot(local: Offset): Offset = (preview?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero) + local

    fun rowAt(p: Offset): Int? = rows.indices.firstOrNull { rowViewports[it]?.contains(p) == true }

    /** The key under [p], only where its row is on screen (scrolled-away keys don't count). */
    private fun keyAt(p: Offset): Pair<Int, EditorKey>? {
        val r = rowAt(p) ?: return null
        val k = rows[r].firstOrNull { keyBounds[it.id]?.contains(p) == true } ?: return null
        return r to k
    }

    /** Selects the row under [p]; returns the key there, if any. */
    fun tap(p: Offset): EditorKey? {
        keyAt(p)?.let { (r, k) ->
            selectedRow = r
            return k
        }
        rowAt(p)?.let { selectedRow = it }
        return null
    }

    // ---- drag & drop -------------------------------------------------------------------

    fun startDrag(p: Offset): Boolean {
        val (r, key) = keyAt(p) ?: return false
        val bounds = keyBounds[key.id] ?: return false
        menuKey = null
        beforeDrag = rows
        dragging = key
        pointer = p
        grab = p - bounds.topLeft
        dragSize = bounds.size
        selectedRow = r
        lastMoveAt = null
        return true
    }

    /** Follows the finger; returns true when the key moved to a new slot. */
    fun dragTo(p: Offset): Boolean {
        pointer = p
        return retarget()
    }

    /** Moves the dragged key to the slot under the finger. Also called after auto-scrolling. */
    fun retarget(): Boolean {
        val key = dragging ?: return false
        val anchor = rowAt(pointer)?.let { rowScroll(it).value.toFloat() } ?: 0f
        val here = pointer + Offset(anchor, 0f) // in row content coordinates: scrolling counts as moving
        lastMoveAt?.let { if ((here - it).getDistance() < moveSlop) return false }
        val (toRow, toIndex) = targetAt(pointer, key) ?: return false
        val (fromRow, fromIndex) = locate(key.id) ?: return false
        if (toRow == fromRow && toIndex == fromIndex) return false
        val m = rows.map { row -> row.filter { it.id != key.id }.toMutableList() }
        m[toRow].add(toIndex.coerceIn(0, m[toRow].size), key)
        rows = m
        selectedRow = toRow
        lastMoveAt = here
        return true
    }

    /** Ends a drag: a key dropped on [deleteZone] is removed. */
    fun endDrag() {
        val before = beforeDrag ?: return
        val key = dragging
        val delete = overDelete
        beforeDrag = null
        dragging = null
        if (delete && key != null) rows = rows.map { row -> row.filterNot { it.id == key.id } }
        if (before.ids() != rows.ids()) {
            pushHistory(before)
            clampSelection()
            onChange(defs())
        }
    }

    private fun locate(id: Long): Pair<Int, Int>? {
        rows.forEachIndexed { r, row -> row.forEachIndexed { i, k -> if (k.id == id) return r to i } }
        return null
    }

    /**
     * The (row, index among that row's other keys) where the dragged key belongs with the finger
     * at [p], or null to stay put: next to the nearest on-screen key of the row under the finger,
     * before or after it depending on which half of it the finger is over.
     */
    private fun targetAt(p: Offset, dragged: EditorKey): Pair<Int, Int>? {
        if (keyBounds[dragged.id]?.contains(p) == true) return null
        val r = rowAt(p) ?: return null
        val viewport = rowViewports[r] ?: return null
        val others = rows[r].filter { it.id != dragged.id }
        if (others.isEmpty()) return r to 0
        val visible = others.mapIndexedNotNull { i, k ->
            keyBounds[k.id]?.takeIf { it.right > viewport.left && it.left < viewport.right }?.let { i to it }
        }
        if (visible.isEmpty()) return null
        val (i, b) = visible.minBy { (_, b) -> maxOf(b.left - p.x, 0f, p.x - b.right) }
        return r to if (p.x < b.center.x) i else i + 1
    }
}

/**
 * Tap and long-press-drag over the bar preview. Handled here rather than on each key because a
 * dragged key's composable is recreated when it moves to another row, which would cancel a
 * gesture attached to it. Once a long press starts a drag, the events are taken in the Initial
 * pass, before the rows' horizontal scrollers see them, so a row cannot scroll under the drag.
 */
private suspend fun PointerInputScope.editorGestures(
    state: KeysEditorState,
    onTap: (EditorKey?) -> Unit,
    onDragStart: () -> Unit,
    onMoved: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var longPress = false
    val up = try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
    } catch (_: PointerEventTimeoutCancellationException) {
        longPress = true
        null
    }
    if (!longPress) {
        // Null when the touch became a scroll of a row.
        if (up != null) {
            up.consume()
            onTap(state.tap(state.toRoot(up.position)))
        }
        return@awaitEachGesture
    }
    val at = currentEvent.changes.firstOrNull { it.id == down.id }?.position ?: down.position
    if (!state.startDrag(state.toRoot(at))) return@awaitEachGesture
    onDragStart()
    try {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            if (state.dragTo(state.toRoot(change.position))) onMoved()
        }
    } finally {
        state.endDrag()
    }
}

private fun special(vararg keys: SpecialKey) = keys.map { ExtraKeyDef.Special(it) }

/** Every key that can be added, one group per category; each group is shown as its own grid. */
private val PALETTE_GROUPS: List<List<ExtraKeyDef>> = listOf(
    special(
        SpecialKey.ESC, SpecialKey.TAB, SpecialKey.ENTER, SpecialKey.BACKSPACE,
        SpecialKey.CTRL, SpecialKey.ALT, SpecialKey.SHIFT, SpecialKey.KEYBOARD, SpecialKey.PASTE, SpecialKey.SNIPPETS,
    ),
    special(
        SpecialKey.LEFT, SpecialKey.DOWN, SpecialKey.UP, SpecialKey.RIGHT, SpecialKey.HOME, SpecialKey.END,
        SpecialKey.PAGE_UP, SpecialKey.PAGE_DOWN, SpecialKey.INSERT, SpecialKey.DELETE,
    ),
    special(
        SpecialKey.CTRL_C, SpecialKey.CTRL_D, SpecialKey.CTRL_Z, SpecialKey.CTRL_L, SpecialKey.CTRL_R,
        SpecialKey.CTRL_A, SpecialKey.CTRL_E, SpecialKey.CTRL_U, SpecialKey.CTRL_W, SpecialKey.CTRL_K,
    ),
    special(
        SpecialKey.F1, SpecialKey.F2, SpecialKey.F3, SpecialKey.F4, SpecialKey.F5, SpecialKey.F6,
        SpecialKey.F7, SpecialKey.F8, SpecialKey.F9, SpecialKey.F10, SpecialKey.F11, SpecialKey.F12,
    ),
    ExtraKeysConfig.SYMBOLS.map { ExtraKeyDef.Text(it) },
    ExtraKeysConfig.MACROS,
)

/** Space between palette keys, and between categories. */
private val PALETTE_GAP = 6.dp
private val PALETTE_GROUP_GAP = 14.dp

/** Narrowest a palette key may get; decides how many fit in a row. */
private val PALETTE_MIN_KEY = 40.dp

/** Palette keys are a bit smaller than bar keys so that all of them fit on screen. */
private val PALETTE_METRICS = KeyMetrics(0.85f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraKeysEditorScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val view = LocalView.current
    val density = LocalDensity.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val snippetList by container.snippets.snippets.collectAsStateWithLifecycle()
    val snippets = remember(snippetList) { snippetList.associateBy { it.id } }
    val state = remember {
        KeysEditorState(container.settings.value.extraKeys.rows) { rows ->
            container.settings.update { it.copy(extraKeys = ExtraKeysConfig(rows)) }
        }
    }
    state.moveSlop = with(density) { 8.dp.toPx() }
    var scale by remember { mutableFloatStateOf(container.settings.value.extraKeysScale) }
    val metrics = remember(scale) { KeyMetrics(scale) }
    var editing by remember { mutableStateOf<EditorKey?>(null) }
    var creatingSnippet by remember { mutableStateOf(false) }

    /**
     * The key sending [text] with [name]: a text key for one character or a built-in macro,
     * otherwise a key for the snippet with that name and text (saved first when there is none).
     */
    fun keyFor(name: String, text: String): ExtraKeyDef {
        val key = ExtraKeyDef.Text(text, name.ifEmpty { null })
        if (!key.isMacro || key in ExtraKeysConfig.MACROS) return key
        val snippet = snippetList.firstOrNull { it.name == name && it.text == text }
            ?: Snippet(name = name, text = text).also { container.snippets.upsert(it) }
        return ExtraKeyDef.SnippetRef(snippet.id)
    }
    var confirmReset by remember { mutableStateOf(false) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val scheme = ColorSchemes.byId(settings.colorSchemeId)
    val barBg = Color(scheme.background)
    val barFg = Color(scheme.foreground)
    val marker = MaterialTheme.colorScheme.primary

    // No text field here: keep the keyboard away, also when focus comes back from the terminal's
    // menu popup (which can bring the terminal's keyboard back up on the way out).
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        val window = (view.context as? Activity)?.window
        if (windowFocused && window != null) WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
    }

    // A key added from the palette may land beyond the visible part of its row: scroll to it.
    LaunchedEffect(state.revealRequest) {
        val (r, b) = state.revealRequest ?: return@LaunchedEffect
        state.revealRequest = null
        val viewport = state.rowViewport(r) ?: return@LaunchedEffect
        val delta = when {
            b.right > viewport.right -> b.right - viewport.right
            b.left < viewport.left -> b.left - viewport.left
            else -> 0f
        }
        if (delta != 0f) state.rowScroll(r).animateScrollBy(delta)
    }

    // Scroll a row sideways while a dragged key is held near its left or right edge.
    val isDragging = state.dragging != null
    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        val edge = with(density) { 48.dp.toPx() }
        val maxSpeed = with(density) { 700.dp.toPx() } // per second
        var last = withFrameNanos { it }
        var scrolled = false
        while (true) {
            val now = withFrameNanos { it }
            // Keys moved under the finger by the last frame's scroll.
            if (scrolled && state.retarget()) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val r = state.rowAt(state.pointer)
            val viewport = r?.let { state.rowViewport(it) }
            var step = 0f
            if (r != null && viewport != null) {
                val x = state.pointer.x
                val ratio = when {
                    x < viewport.left + edge -> -((viewport.left + edge - x) / edge).coerceAtMost(1f)
                    x > viewport.right - edge -> ((x - (viewport.right - edge)) / edge).coerceAtMost(1f)
                    else -> 0f
                }
                step = ratio * maxSpeed * (now - last) / 1_000_000_000f
            }
            last = now
            scrolled = r != null && step != 0f && state.rowScroll(r).scrollBy(step) != 0f
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ek_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { state.undo() }, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.ek_undo))
                    }
                    IconButton(onClick = { confirmReset = true }) { Icon(Icons.Rounded.RestartAlt, stringResource(R.string.ek_reset)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(R.string.ek_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // Key size, right above the bar it changes.
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ek_key_size), style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = scale,
                        onValueChange = { scale = it },
                        onValueChangeFinished = { container.settings.update { it.copy(extraKeysScale = scale) } },
                        valueRange = AppSettings.MIN_KEY_SCALE..AppSettings.MAX_KEY_SCALE,
                        steps = ((AppSettings.MAX_KEY_SCALE - AppSettings.MIN_KEY_SCALE) / 0.05f).roundToInt() - 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(
                        "${(scale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(44.dp),
                    )
                }

                // The bar exactly as it appears above the keyboard (all rows shown).
                CompositionLocalProvider(LocalKeyMetrics provides metrics) {
                    ExtraKeysBarLayout(
                        rows = state.rows,
                        expanded = true,
                        animate = false,
                        background = barBg,
                        toggle = { KeyCap(label = "", icon = Icons.Rounded.KeyboardArrowDown) },
                        edit = { KeyCap(label = "", icon = Icons.Rounded.Edit) },
                        modifier = Modifier
                            .onGloballyPositioned { state.preview = it }
                            .pointerInput(state) {
                                editorGestures(
                                    state = state,
                                    onTap = { k -> if (k != null) state.menuKey = k.id },
                                    onDragStart = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                                    onMoved = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
                                )
                            },
                        rowModifier = { r ->
                            val selected = r == state.selectedRow
                            Modifier
                                .onGloballyPositioned { state.onRowPlaced(r, it.rootRect()) }
                                .drawBehind {
                                    // Selected row: a mark in the bar's left padding, outside the keys.
                                    if (selected) {
                                        val w = 3.dp.toPx()
                                        drawRoundRect(marker, Offset(-w - 0.5.dp.toPx(), 0f), Size(w, size.height), CornerRadius(w / 2))
                                    }
                                }
                        },
                        rowScroll = { r -> state.rowScroll(r) },
                        rowOverlay = { r ->
                            if (state.rows.getOrNull(r)?.isEmpty() == true) {
                                Text(
                                    stringResource(R.string.ek_empty_row),
                                    color = barFg.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        },
                    ) { k ->
                        key(k.id) {
                            EditorKeyCell(state, k, snippets, onEdit = { editing = it })
                        }
                    }
                }

                Palette(
                    state = state,
                    snippets = snippetList,
                    modifier = Modifier.weight(1f),
                    onAdd = { def ->
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        state.add(def)
                    },
                    onNewSnippet = { creatingSnippet = true },
                )
            }

            // The key under the finger while dragging, at its size in the bar.
            state.dragging?.let { k ->
                val w = with(density) { state.dragSize.width.toDp() }
                val h = with(density) { state.dragSize.height.toDp() }
                val delete = state.overDelete
                CompositionLocalProvider(LocalKeyMetrics provides metrics) {
                    KeyCap(
                        label = k.def.label(snippets),
                        icon = k.def.icon(),
                        background = if (delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (delete) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .zIndex(1f)
                            .offset {
                                val p = state.pointer - state.grab - overlayOrigin
                                IntOffset(p.x.roundToInt(), p.y.roundToInt())
                            }
                            .size(w, h)
                            .graphicsLayer {
                                scaleX = 1.08f
                                scaleY = 1.08f
                                shadowElevation = 12.dp.toPx()
                                shape = RoundedCornerShape(metrics.corner)
                            },
                    )
                }
            }
        }
    }

    editing?.let { k ->
        when (val def = k.def) {
            is ExtraKeyDef.Text -> SnippetDialog(
                title = stringResource(R.string.ek_edit_title),
                initialName = def.label.orEmpty(),
                initialText = def.text,
                confirmLabel = stringResource(R.string.action_save),
                onDismiss = { editing = null },
                onConfirm = { name, text ->
                    state.replace(k.id, keyFor(name, text))
                    editing = null
                },
            )
            // Edits the snippet itself, wherever it is used.
            is ExtraKeyDef.SnippetRef -> {
                val snippet = snippets[def.id]
                if (snippet == null) {
                    editing = null
                } else {
                    SnippetDialog(
                        title = stringResource(R.string.snippet_edit),
                        initialName = snippet.name,
                        initialText = snippet.text,
                        confirmLabel = stringResource(R.string.action_save),
                        onDismiss = { editing = null },
                        onConfirm = { name, text ->
                            container.snippets.upsert(snippet.copy(name = name, text = text))
                            editing = null
                        },
                    )
                }
            }
            is ExtraKeyDef.Special -> editing = null
        }
    }

    if (creatingSnippet) {
        SnippetDialog(
            title = stringResource(R.string.ek_snippet_new),
            initialName = "",
            initialText = "",
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { creatingSnippet = false },
            onConfirm = { name, text ->
                state.add(keyFor(name, text))
                creatingSnippet = false
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.ek_reset)) },
            text = { Text(stringResource(R.string.ek_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    state.reset(ExtraKeysConfig.DEFAULT.rows)
                    confirmReset = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A key of the preview: the real key look, plus the tap menu. Fills the slot the row gives it. */
@Composable
private fun EditorKeyCell(state: KeysEditorState, k: EditorKey, snippets: Map<String, Snippet>, onEdit: (EditorKey) -> Unit) {
    val isDragged = state.dragging?.id == k.id
    Box(
        Modifier
            .onGloballyPositioned { state.onKeyPlaced(k.id, it.rootRect()) }
            .graphicsLayer { alpha = if (isDragged) 0.3f else 1f },
        propagateMinConstraints = true,
    ) {
        KeyCap(label = k.def.label(snippets), icon = k.def.icon(), description = k.def.label(snippets))
        DropdownMenu(expanded = state.menuKey == k.id, onDismissRequest = { state.menuKey = null }) {
            if (k.def !is ExtraKeyDef.Special) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = {
                        state.menuKey = null
                        onEdit(k)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    state.menuKey = null
                    state.remove(k.id)
                },
            )
        }
    }
}

/**
 * Below the bar, where the keyboard would be: the row new keys go to (with row actions), and
 * every key that can be added. While a key is dragged it becomes the drop-to-delete area.
 */
@Composable
private fun Palette(
    state: KeysEditorState,
    snippets: List<Snippet>,
    modifier: Modifier,
    onAdd: (ExtraKeyDef) -> Unit,
    onNewSnippet: () -> Unit,
) {
    var rowMenu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().onGloballyPositioned { state.deleteZone = it.rootRect() }) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = 6.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ek_add_to), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.rows.indices.forEach { r ->
                            FilterChip(
                                selected = r == state.selectedRow,
                                onClick = { state.selectedRow = r },
                                label = { Text(stringResource(R.string.ek_row_chip, r + 1)) },
                            )
                        }
                        IconButton(onClick = { state.addRow() }) { Icon(Icons.Rounded.Add, stringResource(R.string.ek_add_row)) }
                    }
                    Box {
                        IconButton(onClick = { rowMenu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.ek_row_actions)) }
                        val r = state.selectedRow
                        DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ek_row_up)) },
                                leadingIcon = { Icon(Icons.Rounded.KeyboardArrowUp, null) },
                                enabled = r > 0,
                                onClick = {
                                    rowMenu = false
                                    state.moveRow(r, -1)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ek_row_down)) },
                                leadingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null) },
                                enabled = r < state.rows.size - 1,
                                onClick = {
                                    rowMenu = false
                                    state.moveRow(r, 1)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ek_delete_row)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                enabled = state.rows.size > 1,
                                onClick = {
                                    rowMenu = false
                                    state.deleteRow(r)
                                },
                            )
                        }
                    }
                }
                CompositionLocalProvider(LocalKeyMetrics provides PALETTE_METRICS) {
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    ) {
                        val maxColumns = ((maxWidth + PALETTE_GAP) / (PALETTE_MIN_KEY + PALETTE_GAP)).toInt().coerceAtLeast(1)
                        Column(verticalArrangement = Arrangement.spacedBy(PALETTE_GROUP_GAP)) {
                            PALETTE_GROUPS.forEach { group -> PaletteGrid(group, emptyMap(), maxColumns, onAdd) }
                            // The user's snippets, each addable as a key.
                            if (snippets.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(PALETTE_GAP)) {
                                    Text(
                                        stringResource(R.string.snippets_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    PaletteGrid(snippets.map { ExtraKeyDef.SnippetRef(it.id) }, snippets.associateBy { it.id }, maxColumns, onAdd)
                                }
                            }
                            KeyCap(
                                label = stringResource(R.string.ek_snippet_new),
                                icon = null,
                                background = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(PALETTE_METRICS.corner))
                                    .clickable(onClick = onNewSnippet),
                            )
                        }
                    }
                }
            }
        }
        if (state.dragging != null) {
            val hot = state.overDelete
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = if (hot) 0.97f else 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(if (hot) 32.dp else 24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.ek_drop_delete),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = if (hot) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * One category of the palette as a grid spanning the full width: as few rows as
 * [maxColumns] allows, the keys spread evenly over them, every key the same width.
 */
@Composable
private fun PaletteGrid(keys: List<ExtraKeyDef>, snippets: Map<String, Snippet>, maxColumns: Int, onAdd: (ExtraKeyDef) -> Unit) {
    val rows = (keys.size + maxColumns - 1) / maxColumns
    val columns = (keys.size + rows - 1) / rows
    Column(verticalArrangement = Arrangement.spacedBy(PALETTE_GAP)) {
        keys.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PALETTE_GAP)) {
                row.forEach { def ->
                    KeyCap(
                        label = def.label(snippets),
                        icon = def.icon(),
                        description = def.label(snippets),
                        background = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(PALETTE_METRICS.corner))
                            .clickable { onAdd(def) },
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
