package app.lernet.ui.routes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.LerNetWarn
import kotlin.math.roundToInt

@Composable
internal fun Modifier.schemaEdgeInput(
    rects: Map<String, NodeRect>,
    nodes: List<RuleNodeRecord>,
    vertical: Boolean,
    scale: Float,
    panning: Boolean,
    selected: SchemaEdge?,
    onSelect: (SchemaEdge?) -> Unit,
): Modifier {
    val latestNodes by rememberUpdatedState(nodes)
    val latestRects by rememberUpdatedState(rects)
    val latestVertical by rememberUpdatedState(vertical)
    val latestScale by rememberUpdatedState(scale)
    val latestPanning by rememberUpdatedState(panning)
    val latestSelected by rememberUpdatedState(selected)
    val latestSelect by rememberUpdatedState(onSelect)
    return pointerInput(Unit) {
        val slop = LerNetDimens.iconButtonSize.toPx() / 2f
        val moveSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (latestPanning) return@awaitEachGesture
            val segments = SchemaEdges.segments(latestNodes, latestRects, latestVertical, latestScale)
            if (latestSelected.buttonContains(segments, down.position.x, down.position.y, slop)) {
                return@awaitEachGesture
            }
            val hit = SchemaEdges.pick(segments, latestRects.values, down.position.x, down.position.y, slop)
            if (hit == null) {
                if (latestSelected != null) latestSelect(null)
                return@awaitEachGesture
            }
            val tapped = followUntilUp(down.position, moveSlop)
            if (tapped) latestSelect(hit)
        }
    }
}

@Composable
internal fun SchemaEdgeLayer(
    rects: Map<String, NodeRect>,
    nodes: List<RuleNodeRecord>,
    vertical: Boolean,
    scale: Float,
    selected: SchemaEdge?,
    selectedChannel: String?,
    canBreak: Boolean,
    onBreak: (SchemaEdge) -> Unit,
) {
    val segments = SchemaEdges.segments(nodes, rects, vertical, scale)
    val pipeLineColor = MaterialTheme.colorScheme.outline
    val highlightedColor = LerNetWarn
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.matchParentSize()) {
            segments.forEach { segment ->
                val chosen = segment.edge == selected
                // The canvas library draws tree links; channel links use the same quiet outline tone.
                val pipe = segment.edge.kind == SchemaEdgeKind.PIPE
                val highlighted = pipe && segment.edge.toId == selectedChannel
                if (!chosen && !pipe) return@forEach
                val color = if (highlighted) highlightedColor else if (pipe) pipeLineColor else SchemaEdges.tone(nodes, segment.edge).ink()
                val width = if (pipe) (if (chosen || highlighted) 2.5.dp else 1.5.dp).toPx()
                    else 6.dp.toPx()
                drawPath(segment.toPath(), color, style = Stroke(width = width, cap = StrokeCap.Round))
                if (pipe) drawCircle(color, radius = if (highlighted) 3.dp.toPx() else 2.dp.toPx(),
                    center = Offset(segment.x1, segment.y1))
            }
        }
        if (canBreak && selected != null) {
            val chosen = segments.firstOrNull { it.edge == selected }
            if (chosen != null) {
                EdgeBreakButton(chosen, onBreak)
            }
        }
    }
}

@Composable
private fun EdgeBreakButton(segment: SchemaSegment, onBreak: (SchemaEdge) -> Unit) {
    val (x, y) = SchemaEdges.pointAt(segment, 0.5f)
    val side = LerNetDimens.iconButtonSize
    IconButton(
        onClick = { onBreak(segment.edge) },
        modifier = Modifier
            .offset {
                IntOffset(
                    (x - side.toPx() / 2f).roundToInt(),
                    (y - side.toPx() / 2f).roundToInt(),
                )
            }
            .size(side),
    ) {
        Icon(
            LerNetSymbols.close(),
            contentDescription = stringResource(R.string.edge_break),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

private fun SchemaEdge?.buttonContains(
    segments: List<SchemaSegment>,
    x: Float,
    y: Float,
    slop: Float,
): Boolean {
    val edge = this ?: return false
    val segment = segments.firstOrNull { it.edge == edge } ?: return false
    val (cx, cy) = SchemaEdges.pointAt(segment, 0.5f)
    val left = cx - slop
    val top = cy - slop
    return NodeRect(left, top, cx + slop, cy + slop).contains(x, y)
}

private suspend fun AwaitPointerEventScope.followUntilUp(
    start: Offset,
    moveSlop: Float,
): Boolean {
    var tapped = true
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Final)
        event.changes.forEach { change ->
            val travel = change.position - start
            if (travel.getDistance() > moveSlop) tapped = false
        }
        if (event.changes.all { !it.pressed }) return tapped
    }
}

private fun SchemaSegment.toPath(): Path = Path().apply {
    moveTo(x0, y0)
    if (orthogonal) {
        val middleY = (y0 + y1) / 2f
        lineTo(x0, middleY)
        lineTo(x1, middleY)
        lineTo(x1, y1)
    } else if (straight) lineTo(x1, y1) else cubicTo(c1x, c1y, c2x, c2y, x1, y1)
}
