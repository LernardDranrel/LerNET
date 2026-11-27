package app.lernet.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal data class RouteMapOverview(
    val cards: Map<String, Rect>,
    val edges: List<Pair<String, String>>,
    val origin: String,
    val selected: String,
    val dragging: Boolean,
) {
    fun fit(bounds: Rect, viewport: IntSize, density: Float, readable: Boolean): RouteCanvasTransform {
        val width = (viewport.width / density - 44f).coerceAtLeast(1f)
        val height = (viewport.height / density - 78f).coerceAtLeast(1f)
        val whole = minOf(1.14f, width / bounds.width.coerceAtLeast(1f), height / bounds.height.coerceAtLeast(1f))
        val zoom = if (readable) maxOf(.90f, whole) else whole
        val anchor = cards[selected] ?: cards.getValue(origin)
        fun center(low: Float, high: Float, extent: Float, target: Float): Float {
            val half = extent / (2 * zoom)
            return if (high - low <= 2 * half) (high + low) / 2 else target.coerceIn(low + half, high - half)
        }
        val point = Offset(center(bounds.left, bounds.right, width, anchor.center.x), center(bounds.top, bounds.bottom, height, anchor.center.y))
        return RouteCanvasTransform(zoom, Offset(viewport.width / 2f, viewport.height / 2f - 20f * density) - point * (zoom * density))
    }
}

@Composable
internal fun BoxScope.RouteMapControls(
    overview: RouteMapOverview,
    bounds: Rect,
    viewport: IntSize,
    transform: RouteCanvasTransform,
    onZoom: (Float) -> Unit,
    onFit: () -> Unit,
    onNavigate: (Offset) -> Unit,
) {
    val density = LocalDensity.current.density
    val border = Color(0xFF44556B)
    val shape = RoundedCornerShape(7.dp)
    if (viewport.width > 440 * density && viewport.height > 230 * density) {
        Canvas(Modifier.align(Alignment.BottomStart).padding(12.dp).size(100.dp, 56.dp)
            .clip(shape).background(Color(0xFF172231)).border(1.dp, border, shape).padding(5.dp)
            .semantics { contentDescription = "Мини-карта схемы" }
            .pointerInput(bounds, viewport, transform.zoom) {
                detectTapGestures { tap ->
                    val scale = minOf(size.width / bounds.width, size.height / bounds.height)
                    val midpoint = Offset(size.width / 2f, size.height / 2f)
                    onNavigate(bounds.center + (tap - midpoint) / scale)
                }
            }) {
            val scale = minOf(size.width / bounds.width, size.height / bounds.height)
            fun project(at: Offset) = center + (at - bounds.center) * scale
            overview.edges.forEach { (parent, child) ->
                drawLine(border, project(overview.cards.getValue(parent).center), project(overview.cards.getValue(child).center))
            }
            overview.cards.forEach { (key, card) ->
                drawRect(if (key == overview.selected) Color(0xFFB3CAFF) else Color(0xFF6D86A3),
                    project(card.topLeft), Size(card.width * scale, card.height * scale))
            }
            val factor = transform.zoom * density
            val topLeft = project(-transform.pan / factor)
            val visible = Rect(topLeft, Size(viewport.width / factor * scale, viewport.height / factor * scale))
                .intersect(Rect(Offset.Zero, size))
            if (!visible.isEmpty) {
                drawRect(Color(0xFFABC5FF).copy(alpha = .12f), visible.topLeft, visible.size)
                drawRect(Color(0xFFC4D8FF), visible.topLeft, visible.size, style = Stroke(1.dp.toPx()))
            }
        }
    }
    Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp), color = Color(0xFF1C2838),
        border = BorderStroke(1.dp, border), shape = shape) {
        Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            MapControl("−", "Уменьшить масштаб") { onZoom(1f / 1.2f) }
            Text("${(transform.zoom * 100).roundToInt()}%", color = Color(0xFFDEE8FB), fontSize = 12.sp)
            MapControl("+", "Увеличить масштаб") { onZoom(1.2f) }
            Spacer(Modifier.width(1.dp).height(16.dp).background(border))
            MapControl("Вписать", "Вписать видимые правила", onFit)
        }
    }
}

@Composable
private fun MapControl(label: String, description: String, action: () -> Unit) {
    Box(Modifier.height(30.dp).widthIn(min = 30.dp).clip(RoundedCornerShape(5.dp))
        .clickable(role = Role.Button, onClick = action).semantics { contentDescription = description }
        .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color(0xFFE1EBFF), fontSize = if (label.length == 1) 17.sp else 12.sp)
    }
}
