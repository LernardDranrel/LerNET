package app.lernet.engine.expert

import app.lernet.routing.policy.PolicyExit
import app.lernet.routing.policy.PolicyProgram
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.ProgramRule
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class AndroidProtectedRuleGateTest {
    @Test
    fun oldAndroidCannotClaimProtectionForNestedPackagePredicate() {
        val program = program("""{"type":"logical","mode":"and","rules":[{"package_name":["org.example.app"]}]}""", true)
        assertThat(AndroidProtectedRuleGate.supports(program, 28)).isFalse()
        assertThat(AndroidProtectedRuleGate.supports(program, 29)).isTrue()
    }

    @Test
    fun domainProtectionDoesNotRequireAppAttribution() {
        assertThat(AndroidProtectedRuleGate.supports(program("""{"domain":["example.invalid"]}""", true), 26)).isTrue()
    }

    @Test
    fun ordinaryPackageRuleNeverClaimsProtectedAttribution() {
        val ordinary = program("""{"package_name":["org.example.app"]}""", false, PolicyTarget.Direct)
        assertThat(AndroidProtectedRuleGate.supports(ordinary, 28)).isTrue()
    }

    @Test
    fun explicitAppBlockRequiresReliableOwnerEvenWithoutProtectedCheckbox() {
        val blocked = program("""{"package_name":["org.example.app"]}""", false)
        assertThat(AndroidProtectedRuleGate.supports(blocked, 28)).isFalse()
        assertThat(AndroidProtectedRuleGate.supports(blocked, 29)).isTrue()
    }

    private fun program(condition: String, protected: Boolean, target: PolicyTarget = PolicyTarget.Block): PolicyProgram = PolicyProgram(
        revision = 1,
        rules = listOf(
            ProgramRule(
                condition = Json.parseToJsonElement(condition).jsonObject,
                target = PolicyExit(target),
                nodeIds = listOf("rule"),
                scopes = listOf(PolicyScope.Device),
                protected = protected,
            ),
        ),
    )
}
