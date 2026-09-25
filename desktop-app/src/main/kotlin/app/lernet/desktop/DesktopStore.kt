package app.lernet.desktop

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.parse.ImportedProfileDraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StoredOutbound(
    val id: String,
    val tag: String,
    val type: String,
    val singBoxJson: String,
) {
    fun model() = NormalizedOutbound(id, tag, type, singBoxJson)
}

@Serializable
data class StoredProfile(
    val id: String,
    val name: String,
    val source: String,
    val outbounds: List<StoredOutbound>,
    val selectedOutboundId: String,
    val dnsJson: String? = null,
    val dnsPolicy: String = "SYSTEM",
    val modeOverride: String? = null,
    val groupId: String? = null,
    val subscriptionUrl: String? = null,
    val canvasLayout: String? = null,
) {
    val selectedOutbound: NormalizedOutbound?
        get() = outbounds.firstOrNull { it.id == selectedOutboundId }?.model()

    companion object {
        fun fromDraft(draft: ImportedProfileDraft) = StoredProfile(
            id = UUID.randomUUID().toString(),
            name = draft.name,
            source = draft.source.name,
            outbounds = draft.outbounds.map { StoredOutbound(it.id, it.tag, it.type, it.singBoxJson) },
            selectedOutboundId = draft.selectedOutboundId,
            dnsJson = draft.dnsJson,
            subscriptionUrl = draft.subscriptionUrl,
        )
    }
}

@Serializable
data class StoredGroup(
    val id: String,
    val name: String,
    val autoSwap: Boolean = false,
    val canvasLayout: String? = null,
)

@Serializable
data class StoredRule(
    val id: String,
    val profileId: String,
    val parentId: String? = null,
    val sortIndex: Int,
    val title: String = "",
    val enabled: Boolean = true,
    val action: String = "PROXY",
    val pipeName: String = "",
    val join: String = "AND",
    val domains: List<String> = emptyList(),
    val domainSuffixes: List<String> = emptyList(),
    val cidrs: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val processes: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val blocksJson: String = "",
)

@Serializable
data class RulePosition(val x: Float, val y: Float)

@Serializable
data class StoredState(
    val profiles: List<StoredProfile> = emptyList(),
    val groups: List<StoredGroup> = emptyList(),
    val rules: List<StoredRule> = emptyList(),
    val selectedProfileId: String? = null,
    val mode: String = "FULL_VPN",
    val corePath: String = "",
    val rulePositions: Map<String, RulePosition> = emptyMap(),
    val tunMtu: Int = 1500,
    val xmuxConcurrency: String = "16-16",
    val directDnsServer: String = "1.1.1.1",
    val logLevel: String = "info",
    val healthUrl: String = "https://www.gstatic.com/generate_204",
    val defaultDnsPolicy: String = "UNDERLAY",
    val journalMaxMb: Int = 100,
)

class DesktopStore(private val directory: Path = defaultDirectory()) {
    private val file = directory.resolve("state.json")
    private val backup = directory.resolve("state.json.bak")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    var recoveredFromBackup: Boolean = false
        private set

    fun load(): StoredState {
        if (!Files.exists(file) && !Files.exists(backup)) return StoredState()
        return try {
            json.decodeFromString(Files.readString(file))
        } catch (primaryError: Exception) {
            if (!Files.exists(backup)) throw primaryError
            val recovered: StoredState = json.decodeFromString(Files.readString(backup))
            if (Files.exists(file)) {
                Files.move(file, directory.resolve("state.json.corrupt-${System.currentTimeMillis()}"))
            }
            Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING)
            recoveredFromBackup = true
            recovered
        }
    }

    fun save(state: StoredState) {
        Files.createDirectories(directory)
        val temp = directory.resolve("state.json.tmp")
        Files.writeString(temp, json.encodeToString(state))
        if (Files.exists(file)) Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun workDirectory(): Path = directory.also(Files::createDirectories)

    companion object {
        fun defaultDirectory(): Path = Path.of(
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString(),
            "LerNET",
        )
    }
}
