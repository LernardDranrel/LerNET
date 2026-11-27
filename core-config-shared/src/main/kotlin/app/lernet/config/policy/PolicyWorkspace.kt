package app.lernet.config.policy

import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyValidator
import app.lernet.routing.policy.normalizedFolderSelections
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class PolicyTemplateChoice(val id: String, val name: String)

/** The selected template uses the workspace's saved/draft pair. Each other template owns its pair here. */
@Serializable
data class StoredPolicyTemplate(val id: String, val name: String, val saved: NetworkPolicy, val draft: NetworkPolicy = saved)

/** Applied revisions are runtime state. Importing a workspace never claims rules are active. */
@Serializable
data class PolicyWorkspace(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val legacy: TransferBundle,
    val saved: NetworkPolicy,
    val draft: NetworkPolicy = saved,
    val selectedTemplateId: String = DEFAULT_TEMPLATE_ID,
    val selectedTemplateName: String = DEFAULT_TEMPLATE_NAME,
    val otherTemplates: List<StoredPolicyTemplate> = emptyList(),
) {
    val templateChoices: List<PolicyTemplateChoice>
        get() = (otherTemplates.map { PolicyTemplateChoice(it.id, it.name) } + PolicyTemplateChoice(selectedTemplateId, selectedTemplateName))
            .sortedWith(compareBy({ it.id != DEFAULT_TEMPLATE_ID }, { it.name.lowercase() }))

    internal val usesNamedTemplates: Boolean
        get() = otherTemplates.isNotEmpty() || selectedTemplateId != DEFAULT_TEMPLATE_ID || selectedTemplateName != DEFAULT_TEMPLATE_NAME

    internal fun currentTemplate() = StoredPolicyTemplate(selectedTemplateId, selectedTemplateName, saved, draft)

    /** Rebuilds the inputs for the host to verify against the last acknowledged fingerprint. */
    fun restartCandidate(templateId: String, revision: Long): PolicyWorkspace? {
        if (revision !in 0..saved.revision) return null
        val template = if (templateId == selectedTemplateId) currentTemplate()
            else otherTemplates.firstOrNull { it.id == templateId } ?: return null
        return PolicyWorkspace(
            legacy = legacy,
            saved = template.saved.copy(revision = revision),
            selectedTemplateId = template.id,
            selectedTemplateName = template.name,
        )
    }

    companion object {
        const val FORMAT = "lernet-network-workspace"
        const val VERSION = 2
        const val DEFAULT_TEMPLATE_ID = "general"
        const val DEFAULT_TEMPLATE_NAME = "Общий"
    }
}

object PolicyWorkspaceCodec {
    private const val MAX_BYTES = 20_000_000
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Older Windows/Android files may still carry groups[].connection / folderPolicies.connection.
        ignoreUnknownKeys = true
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
            val version = header["version"]?.jsonPrimitive?.intOrNull ?: throw UnsupportedPolicyVersion()
            if (version !in 1..PolicyWorkspace.VERSION) throw UnsupportedPolicyVersion()
            fun checkSchemas(entry: JsonObject) {
                listOf("saved", "draft").forEach { field ->
                    val schema = (entry[field] as? JsonObject)?.get("schemaVersion")?.jsonPrimitive?.intOrNull
                    if (schema != null && schema != NetworkPolicy.VERSION) throw UnsupportedPolicyVersion()
                }
            }
            checkSchemas(header)
            (header["otherTemplates"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach(::checkSchemas)
        }
        // The existing parser owns every 1.0.x field and its validation; it is never reimplemented here.
        val workspace = if (TransferCodec.isTransfer(raw)) {
            PolicyMigration.migrate(TransferCodec.decode(raw))
        } else {
            val stored = json.decodeFromString<PolicyWorkspace>(raw)
            if (stored.version !in 1..PolicyWorkspace.VERSION) throw UnsupportedPolicyVersion()
            stored.copy(
                version = PolicyWorkspace.VERSION,
                saved = stored.saved.normalizedFolderSelections(),
                draft = stored.draft.normalizedFolderSelections(),
                otherTemplates = stored.otherTemplates.map {
                    it.copy(saved = it.saved.normalizedFolderSelections(), draft = it.draft.normalizedFolderSelections())
                },
            )
        }
        validate(workspace)
        return workspace
    }

    fun encode(workspace: PolicyWorkspace): String {
        validate(workspace)
        // Older clients can still open a single ordinary scheme, and refuse files with named schemes before writing.
        val serialized = if (workspace.usesNamedTemplates) workspace else workspace.copy(version = 1)
        return json.encodeToString(serialized).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Архив LerNET больше 20 МБ" }
        }
    }

    fun validate(workspace: PolicyWorkspace) {
        if (workspace.version != PolicyWorkspace.VERSION) throw UnsupportedPolicyVersion()
        require(workspace.format == PolicyWorkspace.FORMAT) { "Неподдерживаемый формат рабочей области LerNET" }
        TransferCodec.validate(workspace.legacy)
        val inventory = PolicyMigration.inventory(workspace.legacy)
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        for (template in workspace.otherTemplates + workspace.currentTemplate()) {
            if (template.saved.schemaVersion != NetworkPolicy.VERSION || template.draft.schemaVersion != NetworkPolicy.VERSION) {
                throw UnsupportedPolicyVersion()
            }
            require(template.id.isNotBlank() && ids.add(template.id)) { "Повторяющийся или пустой идентификатор шаблона" }
            require(template.name.isNotBlank() && template.name == template.name.trim()) { "Введите название шаблона" }
            require(names.add(template.name.lowercase())) { "Шаблон с таким названием уже есть" }
            val errors = PolicyValidator.validate(template.saved, inventory)
            require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
            require(template.draft.revision >= 0) { "Неподдерживаемая версия черновика" }
        }
        // Invalid edits are intentionally retained as drafts; they cannot be saved or applied.
    }
}

/** A future schema is not corruption: recovery must not silently replace it with an older backup. */
class UnsupportedPolicyVersion : IllegalArgumentException("Рабочая область создана более новой версией LerNET")
