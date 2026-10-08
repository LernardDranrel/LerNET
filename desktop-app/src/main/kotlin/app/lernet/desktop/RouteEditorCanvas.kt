package app.lernet.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

internal enum class RouteEditorView { SCHEME, LIST }

@Composable
internal fun RouteEditorViewToggle(view: RouteEditorView, onChange: (RouteEditorView) -> Unit) {
    FilterChip(view == RouteEditorView.SCHEME, { onChange(RouteEditorView.SCHEME) }, label = { Text("Схема") })
    FilterChip(view == RouteEditorView.LIST, { onChange(RouteEditorView.LIST) }, label = { Text("Список") })
}

@Composable
internal fun RouteEditorInspector(
    title: String,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier, color = Color(0xFF151D2B), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF3B4961))
    ) {
        Column(Modifier.padding(17.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Color(0xFFA1AEC4), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** A viewport transform only: it never changes rules, targets or saved node positions. */
internal data class RouteCanvasTransform(val zoom: Float = 1f, val pan: Offset = Offset.Zero) {
    fun project(point: Offset, density: Float): Offset = point * (density * zoom) + pan

    fun canvasDelta(screenDelta: Offset, density: Float): Offset = screenDelta / (density * zoom)

    fun zoomAt(requested: Float, anchor: Offset): RouteCanvasTransform {
        val next = requested.coerceIn(.35f, 1.5f)
        return copy(zoom = next, pan = anchor - (anchor - pan) * (next / zoom))
    }

    companion object {
        fun fit(bounds: Rect, viewport: IntSize, density: Float, minimum: Float = .35f): RouteCanvasTransform {
            if (viewport.width <= 0 || viewport.height <= 0) return RouteCanvasTransform()
            val padding = 24f * density
            val toolbarSpace = 64f * density
            val width = (viewport.width - padding * 2).coerceAtLeast(1f)
            val height = (viewport.height - toolbarSpace - padding).coerceAtLeast(1f)
            val zoom = minOf(
                1f, width / (bounds.width.coerceAtLeast(1f) * density),
                height / (bounds.height.coerceAtLeast(1f) * density)
            ).coerceIn(minimum, 1.5f)
            return RouteCanvasTransform(
                zoom,
                Offset(viewport.width / 2f, toolbarSpace) -
                    Offset(bounds.center.x, bounds.top) * (density * zoom)
            )
        }
    }
}

@Composable
internal fun RouteEditorCanvas(
    owner: Any,
    bounds: Rect,
    modifier: Modifier = Modifier,
    onArrange: (() -> Unit)? = null,
    connections: DrawScope.(RouteCanvasTransform) -> Unit,
    content: @Composable BoxScope.(RouteCanvasTransform) -> Unit,
) {
    val density = LocalDensity.current.density
    var viewport by remember(owner) { mutableStateOf(IntSize.Zero) }
    val transformSaver = remember {
        listSaver<RouteCanvasTransform, Float>(
            save = { listOf(it.zoom, it.pan.x, it.pan.y) },
            restore = { RouteCanvasTransform(it[0], Offset(it[1], it[2])) },
        )
    }
    var transform by rememberSaveable(owner, stateSaver = transformSaver) { mutableStateOf(RouteCanvasTransform()) }
    var fitted by rememberSaveable(owner) { mutableStateOf(false) }
    val focus = remember(owner) { FocusRequester() }
    LaunchedEffect(owner) { focus.requestFocus() }
    LaunchedEffect(owner, viewport, bounds, fitted) {
        if (!fitted && viewport.width > 0 && viewport.height > 0) {
            transform = RouteCanvasTransform.fit(bounds, viewport, density)
            fitted = true
        }
    }
    fun zoom(requested: Float, anchor: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
        transform = transform.zoomAt(requested, anchor)
        fitted = true
    }
    Box(
        modifier.clipToBounds().background(Color(0xFF101724)).onSizeChanged { viewport = it }
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                    false
                } else {
                    when (event.key) {
                        Key.Plus, Key.Equals, Key.NumPadAdd -> {
                            zoom(transform.zoom + .15f)
                            true
                        }
                        Key.Minus, Key.NumPadSubtract -> {
                            zoom(transform.zoom - .15f)
                            true
                        }
                        Key.Zero, Key.NumPad0 -> {
                            transform = RouteCanvasTransform.fit(bounds, viewport, density)
                            true
                        }
                        else -> false
                    }
                }
            }.focusable().pointerInput(owner) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            event.changes.firstOrNull()?.let { zoom(transform.zoom - it.scrollDelta.y * .08f, it.position) }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(owner) {
                detectDragGestures { change, amount ->
                    change.consume()
                    transform = transform.copy(pan = transform.pan + amount)
                }
            }
        ) {
            val grid = 28.dp.toPx() * transform.zoom
            if (grid > 12f) {
                var x = transform.pan.x % grid
                while (x < size.width) {
                    drawLine(Color(0xFF3B4961).copy(alpha = .22f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += grid
                }
                var y = transform.pan.y % grid
                while (y < size.height) {
                    drawLine(Color(0xFF3B4961).copy(alpha = .22f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += grid
                }
            }
            connections(transform)
        }
        content(transform)
        Surface(Modifier.align(Alignment.TopEnd).padding(10.dp), color = Color(0xFF151D2B), shape = RoundedCornerShape(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { zoom(transform.zoom - .15f) }) { Text("−") }
                Text("${(transform.zoom * 100).roundToInt()}%", color = Color(0xFFA1AEC4), fontSize = 12.sp)
                TextButton(onClick = { zoom(transform.zoom + .15f) }) { Text("+") }
                TextButton(onClick = {
                    transform = RouteCanvasTransform.fit(bounds, viewport, density)
                    fitted = true
                }) {
                    Text("Вписать")
                }
                onArrange?.let { arrange ->
                    TextButton(onClick = {
                        arrange()
                        fitted = false
                    }) { Text("Выровнять") }
                }
            }
        }
    }
}

@Composable
internal fun RouteEditorPositioned(
    point: Offset,
    transform: RouteCanvasTransform,
    z: Float = 0f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    Box(
        Modifier.offset {
            val screen = transform.project(point, density)
            IntOffset(screen.x.roundToInt(), screen.y.roundToInt())
        }.zIndex(z).graphicsLayer(scaleX = transform.zoom, scaleY = transform.zoom, transformOrigin = TransformOrigin(0f, 0f))
    ) {
        content()
    }
}

/** The child action is below the card, outside its drag surface; hovering either keeps it visible. */
@Composable
internal fun RouteEditorNodeActions(selected: Boolean, onAdd: (() -> Unit)?, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.width(210.dp).height(126.dp).hoverable(interaction)) {
        content()
        if (onAdd != null && (selected || hovered)) {
            FilledIconButton(
                onClick = onAdd,
                modifier = Modifier.offset(x = 83.dp, y = 82.dp).size(44.dp)
                    .semantics { contentDescription = "Добавить дочернее правило" },
            ) { Text("+", fontSize = 20.sp) }
        }
    }
}

/** Drag callbacks use viewport pixels, including when a node is scaled. Cancellation never commits a drop. */
@Composable
internal fun Modifier.routeEditorDrag(
    key: Any,
    zoom: Float = 1f,
    onStart: (Offset) -> Unit = {},
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit = {},
    onCancel: () -> Unit = {},
): Modifier {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    val scale by rememberUpdatedState(zoom)
    return pointerInput(key) {
        detectDragGestures(onDragStart = { start(it * scale) }, onDragEnd = { end() }, onDragCancel = { cancel() }) { change, delta ->
            change.consume()
            drag(delta * scale)
        }
    }
}

@Composable
internal fun RouteEditorNodeFace(
    title: String,
    description: String,
    target: String,
    tone: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    highlighted: Boolean = false,
    channel: Boolean = false,
    ordinal: String? = null,
    highlightTone: Color = Color(0xFF91ABFF),
) {
    Surface(
        modifier.width(210.dp).height(76.dp),
        color = if (muted) {
            Color(0xFF202329)
        } else if (selected) {
            Color(0xFF243056)
        } else if (channel) {
            Color(0xFF302B22)
        } else {
            Color(0xFF151D2B)
        },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            if (selected || highlighted) 2.dp else 1.dp,
            if (selected) {
                Color(0xFF91ABFF)
            } else if (highlighted) {
                highlightTone
            } else if (channel && !muted) {
                Color(0xFFC4A566)
            } else {
                Color(0xFF3B4961)
            }
        )
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title, color = if (muted) Color(0xFFA1AEC4) else Color(0xFFF5F7FB), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp
                )
                Text(description, color = Color(0xFFA1AEC4), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                Text(target, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
            }
            ordinal?.let { Text(it, color = tone, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp)) }
        }
    }
}

internal fun LayoutCoordinates.routeEditorBounds(): Rect = Rect(
    localToWindow(Offset.Zero),
    localToWindow(Offset(size.width.toFloat(), size.height.toFloat())),
)

@Composable
internal fun RouteEditorModal(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    saveText: String = "Готово",
    showCancel: Boolean = true,
    content: @Composable () -> Unit,
) {
    val panel: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.width(800.dp).heightIn(max = 720.dp).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when {
                        event.key == Key.Escape -> {
                            onDismiss()
                            true
                        }
                        event.key == Key.Enter && event.isCtrlPressed && canSave -> {
                            onSave()
                            true
                        }
                        else -> false
                    }
                }
            },
            color = Color(0xFF151D2B), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color(0xFF3B4961)),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, color = Color(0xFFF5F7FB), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = Color(0xFF3B4961))
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(end = 14.dp).verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) { content() }
                    Box(Modifier.matchParentSize()) {
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
                HorizontalDivider(color = Color(0xFF3B4961))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (showCancel) TextButton(onClick = onDismiss) { Text("Отмена · Esc") }
                    Button(onClick = onSave, enabled = canSave) { Text("$saveText · Ctrl+Enter") }
                }
            }
        }
    }
    if (LocalInspectionMode.current) panel() else Dialog(onDismissRequest = onDismiss) { panel() }
}
