package app.lernet.routing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteTreeLayoutTest {
    @Test fun branchesDoNotOverlapAndParentsStayCentered() {
        val layout = RouteTreeLayout.vertical(
            listOf(
                RouteLayoutNode("a", null), RouteLayoutNode("a1", "a"), RouteLayoutNode("a2", "a"),
                RouteLayoutNode("b", null), RouteLayoutNode("b1", "b"), RouteLayoutNode("b2", "b"),
            ),
            column = 240f,
            row = 140f,
        )
        val a = layout.nodes.getValue("a")
        val b = layout.nodes.getValue("b")
        assertThat(a.x).isEqualTo((layout.nodes.getValue("a1").x + layout.nodes.getValue("a2").x) / 2f)
        assertThat(b.x).isEqualTo((layout.nodes.getValue("b1").x + layout.nodes.getValue("b2").x) / 2f)
        assertThat(layout.nodes.getValue("a2").x).isLessThan(layout.nodes.getValue("b1").x)
        assertThat(a.y).isEqualTo(layout.root.y + 140f)
        assertThat(layout.nodes.getValue("a1").y).isEqualTo(a.y + 140f)
    }
}
