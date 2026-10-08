package app.lernet.config.policy

import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferRule
import app.lernet.routing.ConditionCodec
import app.lernet.routing.MatchJoin
import app.lernet.routing.RuleConditions
import app.lernet.routing.RuleMatch
import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyInventory
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Add an Expert schema without modifying the original 1.0.x archive or activating a TUN. */
object PolicyMigration {
    fun migrate(bundle: TransferBundle): PolicyWorkspace {
        TransferCodec.validate(bundle)
        val channels = mutableListOf<PolicyChannel>()
        fun tree(scope: PolicyScope, owner: String, layout: String?): PolicyTree {
            val rules = bundle.rules.filter { it.ownerId == owner }
            val structuralIds = rules.filter { it.enabled && it.parentId != TransferCodec.ORPHAN_PARENT }
                .mapNotNull { it.parentId }.toSet()
            val otherwiseIds = rules.filter { it.parentId != TransferCodec.ORPHAN_PARENT }.groupBy { it.parentId }.values
                .mapNotNull { siblings ->
                    siblings.sortedWith(compareBy<TransferRule> { it.sortIndex }.thenBy { it.id }).lastOrNull()?.takeIf {
                        it.enabled && it.blocksJson.isBlank() && conditions(it).blocks.isEmpty()
                    }?.id
                }.toSet()
            val channelIds = rules.filter { it.action.equals("PROXY", true) && it.pipeName.isNotBlank() }
                .map { it.pipeName.trim() }.distinct().associateWith { name ->
                    val id = channelId(owner, name)
                    channels += PolicyChannel(id, name, scope)
                    id
                }
            return PolicyTree(
                scope,
                rules.map { rule ->
                    PolicyNode(
                        id = rule.id, parentId = rule.parentId.takeUnless { it == TransferCodec.ORPHAN_PARENT },
                        sortIndex = rule.sortIndex, title = rule.title, enabled = rule.enabled,
                        conditions = conditions(rule),
                        target = when (rule.action.uppercase()) {
                            "PROXY" -> if (rule.id in structuralIds) {
                                // Legacy parents are conditions only; a pipe name never executes at a fork.
                                PolicyTarget.CurrentExit
                            } else {
                                channelIds[rule.pipeName.trim()]?.let { PolicyTarget.Channel(it) } ?: PolicyTarget.CurrentExit
                            }
                            "DIRECT" -> PolicyTarget.Direct
                            "BLOCK" -> PolicyTarget.Block
                            else -> error("Validated action must be supported")
                        },
                        detached = rule.parentId == TransferCodec.ORPHAN_PARENT,
                        otherwise = rule.id in otherwiseIds,
                    )
                },
                PolicyTarget.CurrentExit,
                positions(layout, rules, channelIds),
            )
        }
        val trees = bundle.profiles.map { tree(PolicyScope.Profile(it.id), it.id, it.canvasLayout) } +
            bundle.groups.map { tree(PolicyScope.Folder(it.id), "grp_${it.id}", it.canvasLayout) }
        val policy = NetworkPolicy(
            trees = trees, channels = channels,
            folderPolicies = bundle.groups.map { FolderPolicy(it.id, autoSwap = it.autoSwap) },
        )
        return PolicyWorkspace(legacy = bundle, saved = policy, draft = policy)
    }

    fun inventory(bundle: TransferBundle) = PolicyInventory(
        bundle.profiles.map { it.id }.toSet(), bundle.groups.associate { it.id to it.profileIds },
        bundle.profiles.mapNotNull { profile -> ExternalExitProfiles.platformRequirement(profile)?.let { profile.id to it } }.toMap(),
    )

    /** Legacy folder routes override a member's profile tree only when the folder has rules. */
    fun effectiveScope(bundle: TransferBundle, profileId: String): PolicyScope {
        require(bundle.profiles.any { it.id == profileId }) { "Профиль отсутствует" }
        val folder = bundle.groups.firstOrNull { profileId in it.profileIds }
        return if (folder != null && bundle.rules.any { it.ownerId == "grp_${folder.id}" }) {
            PolicyScope.Folder(folder.id)
        } else {
            PolicyScope.Profile(profileId)
        }
    }

    private fun conditions(rule: TransferRule): RuleConditions {
        if (rule.blocksJson.isNotBlank()) return ConditionCodec.decodeStrict(rule.blocksJson)
        val conditions = ConditionCodec.fromMatch(
            RuleMatch(rule.apps, rule.domains, rule.domainSuffixes, rule.ipCidrs, rule.geoip, rule.processes),
        )
        val join = MatchJoin.entries.firstOrNull { it.name.equals(rule.join, ignoreCase = true) } ?: conditions.join
        return conditions.copy(join = join)
    }

    private fun positions(
        layout: String?,
        rules: List<TransferRule>,
        channels: Map<String, String>,
    ): Map<String, PolicyCanvasPoint> {
        val result = linkedMapOf<String, PolicyCanvasPoint>()
        val ids = rules.map { it.id }.toSet()
        val stored = layout?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        stored?.forEach { (key, value) ->
            val point = value as? JsonObject ?: return@forEach
            val x = runCatching { point["x"]?.jsonPrimitive?.floatOrNull }.getOrNull() ?: return@forEach
            val y = runCatching { point["y"]?.jsonPrimitive?.floatOrNull }.getOrNull() ?: return@forEach
            val destination = when {
                key in setOf("root", "node-root") -> PolicyCanvasKeys.ROOT
                key.startsWith("rule:") && key.removePrefix("rule:") in ids -> PolicyCanvasKeys.node(key.removePrefix("rule:"))
                key.startsWith("pipe:") -> channels[key.removePrefix("pipe:").trim()]?.let(PolicyCanvasKeys::channel)
                else -> null
            } ?: return@forEach
            PolicyCanvasPoint(x, y).takeIf { it.isValid() }?.let { result[destination] = it }
        }
        rules.forEach { rule ->
            rule.position?.let { old ->
                val point = PolicyCanvasPoint(old.x, old.y)
                val key = PolicyCanvasKeys.node(rule.id)
                if (point.isValid() && key !in result) result[key] = point
            }
        }
        return result
    }

    private fun channelId(owner: String, name: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$owner\u0000$name".toByteArray(Charsets.UTF_8))
        return "legacy-" + bytes.joinToString("") { "%02x".format(it) }
    }
}
