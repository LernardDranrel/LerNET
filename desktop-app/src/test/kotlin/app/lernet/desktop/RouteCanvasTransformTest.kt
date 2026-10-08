package app.lernet.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteCanvasTransformTest {
    @Test
    fun zoomKeepsThePointUnderTheCursorAtNonDefaultDensity() {
        val density = 1.5f
        val before = RouteCanvasTransform(.7f, Offset(180f, 90f))
        val point = Offset(320f, 210f)
        val cursor = before.project(point, density)
        val after = before.zoomAt(1.25f, cursor)
        assertPoint(after.project(point, density), cursor)
    }

    @Test
    fun zoomLimitsStillKeepTheCursorAnchor() {
        val before = RouteCanvasTransform(1f, Offset(-80f, 32f))
        val point = Offset(50f, 70f)
        val cursor = before.project(point, 2f)
        val smallest = before.zoomAt(-10f, cursor)
        val largest = before.zoomAt(100f, cursor)
        assertThat(smallest.zoom).isEqualTo(.35f)
        assertThat(largest.zoom).isEqualTo(1.5f)
        assertPoint(smallest.project(point, 2f), cursor)
        assertPoint(largest.project(point, 2f), cursor)
    }

    @Test
    fun savedNegativeCoordinatesFitWithoutChangingTheLayout() {
        val bounds = Rect(-340f, -160f, 560f, 240f)
        val viewport = IntSize(1000, 700)
        val fitted = RouteCanvasTransform.fit(bounds, viewport, 1.25f)
        val topLeft = fitted.project(bounds.topLeft, 1.25f)
        val bottomRight = fitted.project(bounds.bottomRight, 1.25f)
        assertThat(topLeft.x).isAtLeast(29.99f)
        assertThat(topLeft.y).isAtLeast(29.99f)
        assertThat(bottomRight.x).isAtMost(970.01f)
        assertThat(bottomRight.y).isAtMost(670.01f)
    }

    @Test
    fun screenDragUsesTheSameCanvasDistanceAtEveryZoom() {
        val distance = Offset(25f, -18f)
        for (zoom in listOf(.35f, .7f, 1f, 1.5f)) {
            val transform = RouteCanvasTransform(zoom, Offset(12f, 34f))
            assertPoint(transform.canvasDelta(distance * (2f * zoom), 2f), distance)
        }
    }

    @Test
    fun unmeasuredViewportDoesNotProduceInvalidCoordinates() {
        assertThat(RouteCanvasTransform.fit(Rect(-20f, -30f, 80f, 90f), IntSize.Zero, 1f))
            .isEqualTo(RouteCanvasTransform())
    }

    private fun assertPoint(actual: Offset, expected: Offset) {
        assertThat(actual.x).isWithin(.001f).of(expected.x)
        assertThat(actual.y).isWithin(.001f).of(expected.y)
    }
}
