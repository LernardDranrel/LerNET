package app.lernet.vpn

import android.content.Context
import app.lernet.config.model.DnsPolicy
import app.lernet.engine.RunMode
import app.lernet.engine.SimpleSessionConfig
import app.lernet.engine.compile.EngineDefaults
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredSimpleSession(
    val generation: String,
    val profileId: String,
    val outboundId: String,
    val compiledJson: String,
    val mode: RunMode,
    val logLevel: String,
    val tunMtu: Int,
    val xmuxConcurrency: String,
    val directDnsServer: String,
    val proxyTag: String?,
    val dnsPolicy: DnsPolicy,
) {
    fun config() = SimpleSessionConfig(profileId, outboundId, compiledJson, mode, logLevel,
        EngineDefaults(tunMtu, xmuxConcurrency, directDnsServer), proxyTag, dnsPolicy)
}

/** A single atomic journal in noBackupFilesDir. Never exported or logged. */
internal class SimpleSessionJournal(private val path: Path) {
    @Synchronized fun read(): StoredSimpleSession? = if (Files.exists(path)) {
        Json.decodeFromString<StoredSimpleSession?>(String(Files.readAllBytes(path), Charsets.UTF_8))
    } else null

    @Synchronized fun write(config: SimpleSessionConfig?) {
        val record = config?.takeIf { it.mode == RunMode.FULL_VPN }?.let {
            StoredSimpleSession(UUID.randomUUID().toString(), it.profileId, it.outboundId, it.compiledJson,
                it.mode, it.logLevel, it.defaults.tunMtu, it.defaults.xmuxConcurrency,
                it.defaults.directDnsServer, it.proxyTag, it.dnsPolicy)
        }
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling("${path.fileName}.tmp")
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use {
            val bytes = java.nio.ByteBuffer.wrap(Json.encodeToString(record).toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) it.write(bytes)
            it.force(true)
        }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

@Singleton
class SimpleSessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val journal = SimpleSessionJournal(context.noBackupFilesDir.resolve("simple-session.json").toPath())
    private var clearedInProcess = false
    internal fun read() = synchronized(journal) {
        if (clearedInProcess) null else journal.read()
    }
    fun write(config: SimpleSessionConfig?) = synchronized(journal) {
        check(config == null || !app.lernet.vpn.expert.ExpertVpnSession.isOwned) { "Expert owns the VPN session" }
        // Even if storage is unavailable, a system start in this process must respect Stop.
        if (config == null || config.mode != RunMode.FULL_VPN) clearedInProcess = true
        journal.write(config)
        if (config?.mode == RunMode.FULL_VPN) clearedInProcess = false
    }
    internal fun clearForExpert() = synchronized(journal) {
        if (app.lernet.vpn.expert.ExpertVpnSession.isOwned && app.lernet.vpn.expert.ExpertServiceRestorer.shouldRestore()) {
            clearedInProcess = true
            journal.write(null)
        }
    }
}
