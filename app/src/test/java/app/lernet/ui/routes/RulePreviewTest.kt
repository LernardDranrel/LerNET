package app.lernet.ui.routes

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RuleConditions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RulePreviewTest {
    private val more: (Int) -> String = { count -> "и ещё $count" }

    @Test
    fun emptyTitleFallsBackToTheFirstConditionThenToTheGenericName() {
        val named = RulePreview.displayTitle("  Обход музыки ", elseRule = false, "Иначе", "Правило", "music")
        val fromCondition = RulePreview.displayTitle("", elseRule = false, "Иначе", "Правило", "не *.evil.com")
        val generic = RulePreview.displayTitle("   ", elseRule = false, "Иначе", "Правило", null)
        assertThat(named).isEqualTo("Обход музыки")
        assertThat(fromCondition).isEqualTo("не *.evil.com")
        assertThat(generic).isEqualTo("Правило")
    }

    @Test
    fun elseKeepsItsFixedLabel() {
        val title = RulePreview.displayTitle("не называть", elseRule = true, "Иначе", "Правило", "example.com")
        assertThat(title).isEqualTo("Иначе")
    }

    @Test
    fun previewKeepsNegationAndCountsTheRest() {
        val countries = listOf(
            PreviewItem(false, "RU"),
            PreviewItem(false, "DE"),
            PreviewItem(true, "не KZ"),
            PreviewItem(false, "FR"),
            PreviewItem(false, "US"),
        )
        val line = RulePreview.fit(countries, more, budget = 24)
        assertThat(line).contains("не KZ")
        assertThat(line).contains("RU")
        assertThat(line).contains("DE")
        assertThat(line).endsWith("и ещё 2")
    }

    @Test
    fun domainsShowTheNegatedPattern() {
        val line = RulePreview.fit(
            listOf(PreviewItem(false, "*.example.com"), PreviewItem(true, "не *.evil.com")),
            more,
        )
        assertThat(line).isEqualTo("*.example.com, не *.evil.com")
    }

    @Test
    fun sameKindBlocksShareOnePreviewLine() {
        val conditions = RuleConditions(
            MatchJoin.OR,
            listOf(
                ConditionBlock(ConditionKind.DOMAIN, listOf("a.com", "!*.b.com")),
                ConditionBlock(ConditionKind.GEOIP, listOf("ru", "!kz")),
                ConditionBlock(ConditionKind.DOMAIN, listOf("c.com")),
            ),
        )
        val grouped = RulePreview.grouped(conditions)
        assertThat(grouped.map { it.first }).containsExactly(ConditionKind.DOMAIN, ConditionKind.GEOIP).inOrder()
        assertThat(grouped.first().second).containsExactly("a.com", "!*.b.com", "c.com").inOrder()
        val first = RulePreview.firstToken(conditions)
        assertThat(first).isEqualTo(ConditionKind.DOMAIN to "a.com")
        assertThat(RulePreview.plain(ConditionKind.GEOIP, "!private", "частные сети")).isEqualTo("частные сети")
        assertThat(RulePreview.plain(ConditionKind.GEOIP, "de", "частные сети")).isEqualTo("DE")
        assertThat(RulePreview.plain(ConditionKind.CIDR, "!10.0.0.0/8", "частные сети")).isEqualTo("10.0.0.0/8")
        assertThat(RulePreview.plain(ConditionKind.APP, "!com.example", "частные сети")).isEqualTo("com.example")
    }

    @Test
    fun aThirdKindLeavesTheCardSoTheExtraCountStaysVisible() {
        val lines = listOf("домен: a.com", "страна: не RU", "сеть: 10.0.0.0/8")
        assertThat(RulePreview.cardLines(lines)).containsExactly("домен: a.com")
        assertThat(RulePreview.cardLines(lines.take(2))).containsExactly("домен: a.com", "страна: не RU").inOrder()
        assertThat(lines.size - RulePreview.cardLines(lines).size).isEqualTo(2)
    }

    @Test
    fun networkAndAppKeepTheNegatedValue() {
        val line = RulePreview.fit(
            listOf(PreviewItem(false, "wifi"), PreviewItem(true, "не ethernet")),
            more,
        )
        assertThat(line).isEqualTo("wifi, не ethernet")
    }
}
