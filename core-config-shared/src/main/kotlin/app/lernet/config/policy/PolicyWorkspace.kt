package app.lernet.config.policy

import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Applied revisions are runtime state. Importing a workspace never claims rules are active. */
@Serializable
data class PolicyWorkspace(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val legacy: TransferBundle,
    val saved: NetworkPolicy,
    val draft: NetworkPolicy = saved,
) {
    companion object {
        const val FORMAT = "lernet-network-workspace"
        const val VERSION = 1
    }
}

object PolicyWorkspaceCodec {
    private const val MAX_BYTES = 20_000_000
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun decode(raw: String): PolicyWorkspace {
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Архив LerNET больше 20 МБ" }
        val header = json.parseToJsonElement(raw) as? JsonObject ?: error("Рабочая область должна быть JSON-объектом")
        if (header["format"]?.jsonPrimitive?.contentOrNull == TransferBundle.FORMAT &&
            header["version"]?.jsonPrimitive?.intOrNull != TransferBundle.VERSION
        ) {
            throw UnsupportedPolicyVersion()
        }
        if (header["format"]?.jsonPrimitive?.contentOrNull == PolicyWorkspace.FORMAT) {
            if (header["version"]?.jsonPrimitive?.intOrNull != PolicyWorkspace.VERSION) throw UnsupportedPolicyVersion()
            listOf("saved", "draft").forEach { field ->
                val schema = (header[field] as? JsonObject)?.get("schemaVersion")?.jsonPrimitive?.intOrNull
                if (schema != null && schema != NetworkPolicy.VERSION) throw UnsupportedPolicyVersion()
            }
        }
        // The existing parser owns every 1.0.x field and its validation; it is never reimplemented here.
        val workspace = if (TransferCodec.isTransfer(raw)) {
            PolicyMigration.migrate(TransferCodec.decode(raw))
        } else {
            json.decodeFromString<PolicyWorkspace>(raw)
        }
        validate(workspace)
        return workspace
    }

    fun encode(workspace: PolicyWorkspace): String {
        validate(workspace)
        return json.encodeToString(workspace).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Архив LerNET больше 20 МБ" }
        }
    }

    fun validate(workspace: PolicyWorkspace) {
        if (workspace.version != PolicyWorkspace.VERSION ||
            workspace.saved.schemaVersion != NetworkPolicy.VERSION ||
            workspace.draft.schemaVersion != NetworkPolicy.VERSION
        ) {
            throw UnsupportedPolicyVersion()
        }
        require(workspace.format == PolicyWorkspace.FORMAT) { "Неподдерживаемый формат рабочей области LerNET" }
        TransferCodec.validate(workspace.legacy)
        val inventory = PolicyMigration.inventory(workspace.legacy)
        val errors = PolicyValidator.validate(workspace.saved, inventory)
        require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
        require(workspace.draft.schemaVersion == NetworkPolicy.VERSION && workspace.draft.revision >= 0) {
            "Неподдерживаемая версия черновика"
        }
        // Invalid edits are intentionally retained as drafts; they cannot be saved or applied.
    }
}

/** A future schema is not corruption: recovery must not silently replace it with an older backup. */
class UnsupportedPolicyVersion : IllegalArgumentException("Рабочая область создана более новой версией LerNET")
