package app.lernet.ui.expert

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil
import kotlin.math.floor

/** World and viewport coordinates are in dp. Only actual gestures cross the pixel boundary. */
internal data class BranchMapLayout(val count: Int, val nodeHeight: Float) {
    val nodeWidth = 226f
    val indent = 30f
    val rowStep = nodeHeight + 18f
    val width = nodeWidth + if (count > 1) indent else 0f
    val height = (count - 1) * rowStep + nodeHeight

    fun position(row: Int) = Offset(if (row == 0) 0f else indent, row * rowStep)

    fun visibleRows(top: Float, bottom: Float): IntRange {
        val first = ceil((top - nodeHeight) / rowStep).toInt().coerceAtLeast(0)
        val last = floor(bottom / rowStep).toInt().coerceAtMost(count - 1)
        return first..last
    }

    fun rowAt(point: Offset): Int? {
        val row = floor(point.y / rowStep).toInt()
        if (row !in 0 until count) return null
        val origin = position(row)
        return row.takeIf { point.x in origin.x..origin.x + nodeWidth && point.y - origin.y <= nodeHeight }
    }

    fun fit(viewport: Size): BranchMapCamera {
        val scale = minOf(1.17f, (viewport.width - 24f).coerceAtLeast(1f) / width,
            (viewport.height - 24f).coerceAtLeast(1f) / height)
        return BranchMapCamera(scale, Offset(width / 2, height / 2))
    }
}

internal data class BranchMapCamera(val scale: Float, val center: Offset) {
    fun screen(world: Offset, viewport: Size): Offset = viewport.middle() + (world - center) * scale
    fun world(screen: Offset, viewport: Size): Offset = center + (screen - viewport.middle()) / scale

    fun gesture(viewport: Size, anchor: Offset, pan: Offset, factor: Float, minimum: Float): BranchMapCamera {
        val next = (scale * factor).coerceIn(minimum, 2.25f)
        val fixed = world(anchor, viewport)
        return BranchMapCamera(next, fixed - (anchor + pan - viewport.middle()) / next)
    }
}

internal fun Size.middle() = Offset(width / 2, height / 2)
