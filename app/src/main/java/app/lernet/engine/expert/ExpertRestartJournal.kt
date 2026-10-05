package app.lernet.engine.expert

import app.lernet.config.policy.PolicyWorkspace
import app.lernet.config.policy.PolicyWorkspaceCodec
import app.lernet.engine.compile.EngineDefaults
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Only a digest is persisted; neither credentials nor an obsolete policy can be restored from this journal. */
internal object ExpertRestartFingerprint {
    fun calculate(
        workspace: PolicyWorkspace,
        defaults: EngineDefaults,
        compileInputs: List<String>,
        ruleSetDigests: Map<String, String> = emptyMap(),
    ): String {
        // Drafts are independently durable edits, not effective inputs to the acknowledged generation.
        val effective = Json.parseToJsonElement(PolicyWorkspaceCodec.encode(workspace.copy(draft = workspace.saved)))
        val body = buildJsonObject {
            put("workspace", effective)
            put(
                "defaults",
                buildJsonObject {
                    put("tun_mtu", defaults.tunMtu)
                    put("xmux_concurrency", defaults.xmuxConcurrency)
                    put("direct_dns_server", defaults.directDnsServer)
                }
            )
            put("compile_inputs", JsonArray(compileInputs.map(::JsonPrimitive)))
            put("rule_sets", JsonObject(ruleSetDigests.mapValues { JsonPrimitive(it.value) }))
        }
        return digest(canonical(body).toString().toByteArray(Charsets.UTF_8))
    }

    fun ruleSetDigests(directory: String): Map<String, String> {
        if (directory.isBlank()) return emptyMap()
        val root = File(directory)
        if (!root.exists()) return emptyMap()
        val files = requireNotNull(root.listFiles()) { "Rule-set inventory cannot be read" }
            .filter { it.isFile && it.extension == "srs" }.sortedBy { it.name }
        validateRuleSetBudget(files.map(File::length))
        val buffer = ByteArray(65_536)
        return files.associate { file ->
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    hash.update(buffer, 0, size)
                }
            }
            file.name to hex(hash.digest())
        }
    }

    internal fun validateRuleSetBudget(fileSizes: List<Long>) {
        // The packaged Android catalogue currently has 238 small files. Bound total work
        // and each file independently, while allowing normal catalogue growth.
        require(fileSizes.size <= 4_096 && fileSizes.all { it in 0..64_000_000L } && fileSizes.sum() <= 256_000_000L) {
            "Rule-set inventory exceeds restart bounds"
        }
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal interface ExpertRestartStorage {
    fun read(): String?
    fun write(raw: String)
}

internal class ExpertRestartFileStorage(private val file: File) : ExpertRestartStorage {
    override fun read(): String? {
        if (!file.exists()) return null
        require(file.length() <= 4_096) { "Restart journal exceeds bounds" }
        return file.readText(Charsets.UTF_8)
    }

    override fun write(raw: String) {
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Restart journal directory cannot be created" }
        val temporary = File(directory, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(raw.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Before mutation revoke eligibility durably. Only a real ACK may promote a matching input digest. */
internal class ExpertRestartJournal(private val storage: ExpertRestartStorage) {
    private var blockedInProcess = false
    private var transitionInvalidated = false

    @Synchronized
    fun invalidate() {
        blockedInProcess = true
        transitionInvalidated = false
        storage.write(record(eligible = false))
        transitionInvalidated = true
    }

    @Synchronized
    fun promoteActualAck(fingerprint: String, revision: Long): Boolean {
        require(fingerprint.matches(Regex("[a-f0-9]{64}")) && revision >= 0)
        check(transitionInvalidated) { "Restart eligibility was not invalidated before mutation" }
        return try {
            storage.write(record(true, fingerprint, revision))
            blockedInProcess = false
            transitionInvalidated = false
            true
        } catch (_: Exception) {
            blockedInProcess = true
            false
        }
    }

    @Synchronized
    fun matches(fingerprint: String): Boolean {
        if (blockedInProcess) return false
        return runCatching {
            val raw = storage.read() ?: return@runCatching false
            require(raw.length <= 4_096)
            val body = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching false
            val version = body["version"] as? JsonPrimitive
            val eligible = body["eligible"] as? JsonPrimitive
            val captured = body["fingerprint"] as? JsonPrimitive
            val revision = body["revision"] as? JsonPrimitive
            version?.takeUnless { it.isString }?.longOrNull == 1L &&
                eligible?.takeUnless { it.isString }?.booleanOrNull == true &&
                captured?.takeIf { it.isString }?.content == fingerprint &&
                revision?.takeUnless { it.isString }?.longOrNull?.let { it >= 0 } == true
        }.getOrDefault(false)
    }

    private fun record(eligible: Boolean, fingerprint: String? = null, revision: Long? = null): String = buildJsonObject {
        put("version", 1)
        put("eligible", eligible)
        if (fingerprint != null) put("fingerprint", fingerprint)
        if (revision != null) put("revision", revision)
    }.toString()
}
