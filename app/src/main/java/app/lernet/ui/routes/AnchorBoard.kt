package app.lernet.ui.routes

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.xingray.compose.infinitecanvas.CanvasNodeState

/**
 * Unscaled node sizes plus the canvas node states.
 * Screen position is [SchemaEdges.screenRects], the same scale and offset the
 * library uses for the grey connector. Window coordinates drift on zoom.
 */
internal class AnchorBoard {
    val sizes = mutableStateMapOf<String, Pair<Float, Float>>()
    val states = mutableMapOf<String, CanvasNodeState>()

    fun bind(id: String, state: CanvasNodeState) {
        states[id] = state
    }
}

internal fun Modifier.trackNode(board: AnchorBoard, id: String): Modifier = onGloballyPositioned { coords ->
    board.sizes[id] = coords.size.width.toFloat() to coords.size.height.toFloat()
}
