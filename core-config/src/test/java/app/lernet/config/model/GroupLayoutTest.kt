package app.lernet.config.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GroupLayoutTest {
    @Test
    fun dropIntoSecondGroupRemovesTheFirst() {
        val groups = listOf(
            Group("a", "A", listOf("p1", "p2")),
            Group("b", "B", emptyList()),
        )
        val next = GroupLayout.moveProfile(groups, "p1", "b", 0)
        assertThat(next.first { it.id == "a" }.profileIds).containsExactly("p2")
        assertThat(next.first { it.id == "b" }.profileIds).containsExactly("p1")
    }

    @Test
    fun reorderInsideGroupIsAPermutation() {
        val groups = listOf(Group("a", "A", listOf("p1", "p2", "p3")))
        val next = GroupLayout.moveProfile(groups, "p1", "a", 2)
        assertThat(next.single().profileIds).containsExactly("p2", "p3", "p1").inOrder()
    }

    @Test
    fun moveGroupChangesOrderOnly() {
        val groups = listOf(Group("a", "A", listOf("p1")), Group("b", "B", emptyList()))
        val next = GroupLayout.moveGroup(groups, "b", 0)
        assertThat(next.map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(next.first { it.id == "a" }.profileIds).containsExactly("p1")
    }
}
