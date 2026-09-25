package app.lernet.routing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteElseTest {
    @Test
    fun blankConditionsMarkElseAndEmptyBlocksAreABlankRule() {
        val elseNode = RuleNode(
            id = "else",
            parentId = null,
            enabled = true,
            sortIndex = 1,
            match = RuleMatch(),
            action = RouteAction.PROXY,
            pipeName = "",
            conditions = null,
        )
        val draft = elseNode.copy(
            id = "draft",
            sortIndex = 0,
            conditions = RuleConditions(MatchJoin.OR, emptyList()),
        )
        assertThat(RouteElse.isElse(elseNode)).isTrue()
        assertThat(RouteElse.isElse(draft)).isFalse()
        assertThat(RouteElse.blankRules(listOf(draft)).map { it.message })
            .containsExactly(RouteElse.BLANK)
        assertThat(RouteElse.errors(listOf(draft)).map { it.message })
            .contains(RouteElse.MISSING)
        assertThat(RouteElse.errors(listOf(draft, elseNode))).isEmpty()
    }
}
