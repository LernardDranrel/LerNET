package app.lernet.ui.expert

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BranchMapLayoutTest {
    @Test
    fun fitIncludesTheLastSiblingEvenBelowOnePercent() {
        val layout = BranchMapLayout(count = 10_001, nodeHeight = 77f)
        val viewport = Size(328f, 380f)
        val camera = layout.fit(viewport)
        val top = camera.screen(layout.position(0), viewport)
        val last = layout.position(layout.count - 1)
        val end = camera.screen(last + Offset(layout.nodeWidth, layout.nodeHeight), viewport)

        assertThat(camera.scale).isLessThan(.01f)
        assertThat(top.x).isAtLeast(0f)
        assertThat(top.y).isAtLeast(0f)
        assertThat(end.x).isAtMost(viewport.width)
        assertThat(end.y).isAtMost(viewport.height)
        assertThat(layout.rowAt(last + Offset(10f, 10f))).isEqualTo(layout.count - 1)
        assertThat(layout.rowAt(last - Offset(0f, 5f))).isNull()
    }

    @Test
    fun pinchKeepsTheWorldPointUnderTheMovingFingersEvenAtTheZoomLimit() {
        val viewport = Size(336f, 400f)
        val camera = BranchMapCamera(2f, Offset(80f, 200f))
        val centroid = Offset(120f, 100f)
        val pan = Offset(20f, 40f)
        val previous = camera.world(centroid, viewport)
        val zoomed = camera.gesture(viewport, centroid, pan, 3f, .25f)
        val projected = zoomed.screen(previous, viewport)

        assertThat(zoomed.scale).isEqualTo(2.25f)
        assertThat(projected.x).isWithin(.001f).of(centroid.x + pan.x)
        assertThat(projected.y).isWithin(.001f).of(centroid.y + pan.y)
    }
}
