package app.lernet.engine.expert

import app.lernet.routing.policy.PolicyProgram
import app.lernet.routing.policy.PolicyTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Android 10 introduced the VPN owner's supported UID lookup; old procfs is not a protection proof. */
object AndroidProtectedRuleGate {
    fun requiresModernPackageAttribution(program: PolicyProgram): Boolean =
        program.rules.any { (it.protected || it.target.target == PolicyTarget.Block) && containsPackageName(it.condition) }

    fun supports(program: PolicyProgram, sdk: Int): Boolean = sdk >= 29 || !requiresModernPackageAttribution(program)

    private fun containsPackageName(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.containsKey("package_name") ||
            element.containsKey("package_name_regex") ||
            element.values.any(::containsPackageName)
        is JsonArray -> element.any(::containsPackageName)
        else -> false
    }
}
