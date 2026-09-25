package app.lernet.ui.routes

import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.RuleConditions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleRecordEditTest {
    @Test
    fun removingSelectedCountryAndItsBlockClearsCompiledGeoip() {
        val original = RuleNodeRecord(
            id = "rule",
            profileId = "profile",
            parentId = null,
            enabled = true,
            sortIndex = 0,
            action = "proxy",
            apps = emptyList(),
            domains = listOf("example.com"),
            domainSuffixes = emptyList(),
            ipCidrs = emptyList(),
            geoip = listOf("ru"),
        )
        val withoutCountry = original.withConditions(
            RuleConditions(
                blocks = listOf(
                    ConditionBlock(ConditionKind.DOMAIN, listOf("example.com")),
                    ConditionBlock(ConditionKind.GEOIP, GeoIpCodes.clear(listOf("ru"), "ru")),
                ),
            ),
        )
        assertThat(withoutCountry.geoip).isEmpty()
        assertThat(withoutCountry.domains).containsExactly("example.com")

        val withoutBlock = withoutCountry.withConditions(
            withoutCountry.shownConditions().copy(
                blocks = withoutCountry.shownConditions().blocks.filterNot { it.kind == ConditionKind.GEOIP },
            ),
        )
        assertThat(withoutBlock.shownConditions().blocks.map { it.kind }).containsExactly(ConditionKind.DOMAIN)
        assertThat(withoutBlock.geoip).isEmpty()
        assertThat(RuleSheetGate.errors(withoutBlock.shownConditions())).isEmpty()
    }
}
