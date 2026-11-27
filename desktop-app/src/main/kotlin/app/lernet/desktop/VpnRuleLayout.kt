package app.lernet.desktop

import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

/** Keep the archive's channel pins and metadata when changing rule positions. */
internal object VpnRuleLayout {
    private fun raw(state: StoredState, owner: String): String? =
        state.profiles.find { it.id == owner }?.canvasLayout
            ?: state.groups.find { "grp_" + it.id == owner }?.canvasLayout

    private fun fields(raw: String?): Map<String, kotlinx.serialization.json.JsonElement> =
        raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }.orEmpty()

    fun read(state: StoredState, owner: String): Map<String, PolicyCanvasPoint> {
        val fields = fields(raw(state, owner))
        fun point(key: String): PolicyCanvasPoint? {
            val objectValue = fields[key] as? JsonObject ?: return null
            val x = (objectValue["x"] as? JsonPrimitive)?.floatOrNull ?: return null
            val y = (objectValue["y"] as? JsonPrimitive)?.floatOrNull ?: return null
            return PolicyCanvasPoint(x, y).takeIf { it.isValid() }
        }
        return buildMap {
            (point("node-root") ?: point("root"))?.let { put(PolicyCanvasKeys.ROOT, it) }
            state.rules.filter { it.profileId == owner }.forEach { rule ->
                val old = state.rulePositions[rule.id]?.let { PolicyCanvasPoint(it.x, it.y) }
                (old?.takeIf { it.isValid() } ?: point("rule:" + rule.id))?.let { put(PolicyCanvasKeys.node(rule.id), it) }
            }
        }
    }

    fun write(state: StoredState, owner: String, points: Map<String, PolicyCanvasPoint>): StoredState {
        val ids = state.rules.filter { it.profileId == owner }.mapTo(mutableSetOf()) { it.id }
        val positions = state.rulePositions.toMutableMap().apply { keys.removeAll(ids) }
        val layout = fields(raw(state, owner)).filterKeys {
            it != "root" && it != "node-root" && !it.startsWith("rule:")
        }.toMutableMap()
        points.forEach { (key, point) ->
            val ruleId = PolicyCanvasKeys.nodeId(key)
            if (!point.isValid() || (key != PolicyCanvasKeys.ROOT && ruleId !in ids)) return@forEach
            val archiveKey = if (key == PolicyCanvasKeys.ROOT) "node-root" else "rule:" + ruleId
            layout[archiveKey] = buildJsonObject {
                put("x", point.x)
                put("y", point.y)
            }
            if (key != PolicyCanvasKeys.ROOT) positions[ruleId] = RulePosition(point.x, point.y)
        }
        val encoded = layout.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString() }
        return state.copy(
            rulePositions = positions,
            profiles = state.profiles.map { if (it.id == owner) it.copy(canvasLayout = encoded) else it },
            groups = state.groups.map { if ("grp_" + it.id == owner) it.copy(canvasLayout = encoded) else it }
        )
    }
}
