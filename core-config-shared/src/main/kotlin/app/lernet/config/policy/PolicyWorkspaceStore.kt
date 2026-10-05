package app.lernet.config.policy

import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Separate storage keeps 1.0.x usable during migration; identical on Android and Windows. */
class PolicyWorkspaceStore(private val file: Path) {
    private val backup = file.resolveSibling(file.fileName.toString() + ".bak")
    private val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
    private val backupTemp = file.resolveSibling(file.fileName.toString() + ".bak.tmp")
    private val pendingImport = file.resolveSibling(file.fileName.toString() + ".import-pending")
    private val pendingImportTemp = file.resolveSibling(file.fileName.toString() + ".import-pending.tmp")

    @Synchronized
    fun load(): PolicyWorkspace? {
        if (!Files.exists(file) && !Files.exists(backup)) return null
        return try {
            PolicyWorkspaceCodec.decode(read(file))
        } catch (future: UnsupportedPolicyVersion) {
            throw future
        } catch (primary: Exception) {
            if (!Files.exists(backup)) throw primary
            PolicyWorkspaceCodec.decode(read(backup))
        }
    }

    @Synchronized
    fun save(workspace: PolicyWorkspace) {
        // Validate before touching either copy. A corrupt draft cannot replace a last good saved policy.
        val raw = PolicyWorkspaceCodec.encode(workspace)
        Files.createDirectories(file.toAbsolutePath().parent)
        writeSynced(temp, raw)
        if (Files.exists(file)) {
            // Never replace a valid backup with an unreadable primary after recovery.
            val existing = runCatching { PolicyWorkspaceCodec.decode(read(file)) }
            if (existing.exceptionOrNull() is UnsupportedPolicyVersion) throw existing.exceptionOrNull()!!
            if (existing.isSuccess) {
                writeSynced(backupTemp, read(file))
                replace(backupTemp, backup)
            }
        }
        replace(temp, file)
    }

    @Synchronized
    fun savePendingImport(plan: PolicyImportPlan) {
        val encoded = Json.encodeToString(
            PendingImport(PolicyWorkspaceCodec.encode(plan.before), PolicyWorkspaceCodec.encode(plan.after)),
        )
        Files.createDirectories(file.toAbsolutePath().parent)
        writeSynced(pendingImportTemp, encoded)
        replace(pendingImportTemp, pendingImport)
    }

    @Synchronized
    fun loadPendingImport(): PolicyImportPlan? {
        if (!Files.exists(pendingImport)) return null
        require(Files.size(pendingImport) <= 100_000_000) { "Журнал импорта слишком велик" }
        val raw = Files.readAllBytes(pendingImport).toString(Charsets.UTF_8)
        val parsed = Json.decodeFromString<PendingImport>(raw)
        return PolicyImportPlan(PolicyWorkspaceCodec.decode(parsed.before), PolicyWorkspaceCodec.decode(parsed.after))
    }

    @Synchronized
    fun clearPendingImport() {
        Files.deleteIfExists(pendingImport)
    }

    private fun writeSynced(path: Path, raw: String) {
        FileOutputStream(path.toFile()).use { output ->
            output.write(raw.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun replace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun read(path: Path): String {
        require(Files.size(path) <= 20_000_000) { "Архив LerNET больше 20 МБ" }
        return Files.readAllBytes(path).toString(Charsets.UTF_8)
    }
}

@Serializable
private data class PendingImport(val before: String, val after: String)
