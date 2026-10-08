package app.lernet.ui.routes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.ui.icons.LerNetSymbols
import io.github.xingray.compose.infinitecanvas.CanvasMode
import io.github.xingray.compose.infinitecanvas.CanvasNode
import io.github.xingray.compose.infinitecanvas.InfiniteCanvas
import io.github.xingray.compose.infinitecanvas.InfiniteCanvasConfig
import io.github.xingray.compose.infinitecanvas.InfiniteCanvasState

/** One viewport, grid and touch engine for VPN, Expert and simulator graphs. */
@Composable
internal fun rememberSchemaCanvasState(key: Any): InfiniteCanvasState {
    var savedScale by rememberSaveable(key) { mutableStateOf(1f) }
    var savedX by rememberSaveable(key) { mutableStateOf(0f) }
    var savedY by rememberSaveable(key) { mutableStateOf(0f) }
    var savedPan by rememberSaveable(key) { mutableStateOf(true) }
    val state = remember(key) { InfiniteCanvasState().also {
        it.switchMode(if (savedPan) CanvasMode.Pan else CanvasMode.Select)
        it.viewport.scale = savedScale
        it.viewport.offset = Offset(savedX, savedY)
    } }
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.viewport.scale, state.viewport.offset.x, state.viewport.offset.y) }.collect {
            savedScale = it.first; savedX = it.second; savedY = it.third
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.canvasMode == CanvasMode.Pan }.collect { savedPan = it }
    }
    return state
}

@Composable
internal fun SchemaCanvas(state: InfiniteCanvasState, nodes: List<CanvasNode>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    InfiniteCanvas(modifier = modifier, state = state, nodes = nodes, config = InfiniteCanvasConfig(
        showGrid = true, showBottomControls = false, backgroundColor = scheme.surface,
        gridColor = scheme.outline.copy(alpha = 0.35f),
    ))
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SchemaCanvasControls(
    state: InfiniteCanvasState, onCenter: () -> Unit,
    onAlign: (() -> Unit)? = null, onHelp: (() -> Unit)? = null, zoomAnchor: Offset = Offset.Zero,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = state.canvasMode == CanvasMode.Pan, onCheckedChange = {
                state.switchMode(if (it) CanvasMode.Pan else CanvasMode.Select)
            }) { Icon(LerNetSymbols.pan(), contentDescription = stringResource(R.string.canvas_pan)) }
            val zoomOutLabel = stringResource(R.string.expert_graph_zoom_out)
            IconButton({ state.viewport.zoomBy(0.8f, zoomAnchor) }, enabled = state.viewport.scale > 0.1f,
                modifier = Modifier.semantics { contentDescription = zoomOutLabel }) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text("${state.viewport.scalePercent}%", style = MaterialTheme.typography.labelLarge)
            IconButton({ state.viewport.zoomBy(1.25f, zoomAnchor) }, enabled = state.viewport.scale < 5f) {
                Icon(LerNetSymbols.add(), contentDescription = stringResource(R.string.expert_graph_zoom_in))
            }
            onHelp?.let { IconButton(it) { Icon(LerNetSymbols.help(), contentDescription = stringResource(R.string.canvas_help)) } }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onCenter) { Text(stringResource(R.string.expert_graph_root)) }
            onAlign?.let { TextButton(it) { Text(stringResource(R.string.expert_align_graph)) } }
        }
    }
}
