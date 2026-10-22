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
    val rulePositions: Map<String, RulePosition> = emptyMap(),
    val tunMtu: Int = 1500,
    /** Written by builds before the XHTTP channel setting; kept so old files load, not used. */
    val xmuxConcurrency: String = "",
    /** XHTTP connections per server when a config has no xmux of its own: 2, or 1 with «Один канал». */
    val xmuxChannels: Int = app.lernet.engine.compile.XhttpMode.DEFAULT_CHANNELS,
    val directDnsServer: String = "1.1.1.1",
    val logLevel: String = "info",
    val healthUrl: String = "https://www.gstatic.com/generate_204",
    val defaultDnsPolicy: String = "TUNNEL",
    val journalMaxMb: Int = 100,
    /** VPN mode: Windows keeps traffic closed outside LerNET while the core restarts. */
    val crashGuard: Boolean = true,
    /** «Запускать при входе в систему»: start in the tray at sign-in and bring back a VPN left on. */
    val autostart: Boolean = true,
    /** VPN mode: Windows may not ask DNS or use IPv6 past the VPN adapter. */
    val strictRoute: Boolean = true,
    /** Own login/password for the local proxy; empty means a new random pair for every connection. */
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /** Opt-in: split the first TLS message into TLS records (core `record_fragment`). */
    val tlsRecordFragment: Boolean = false,
    /** Reality key exchange: "" (as the fingerprint), "hybrid" or "classical". */
    val realityKeyShare: String = "",
    /** Audit R3: programs (chrome.exe) that go past the VPN in VPN mode, chosen in «Программы через VPN». */
    val bypassApps: List<String> = emptyList(),
)

/** One place that turns stored preferences into assembler defaults (VPN, Expert and probes alike). */
fun StoredState.engineDefaults(strict: Boolean = strictRoute) = app.lernet.engine.compile.EngineDefaults(
    tunMtu = tunMtu, xmuxChannels = xmuxChannels.coerceIn(1, 2), directDnsServer = directDnsServer, strictRoute = strict,
    tlsRecordFragment = tlsRecordFragment,
    realityKeyShare = app.lernet.engine.compile.RealityKeyShare.fromStorage(realityKeyShare).storage,
    bypassApps = bypassApps,
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
