package app.lernet.desktop

import app.lernet.config.policy.PolicyWorkspace
import app.lernet.engine.compile.EngineDefaults
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class DesktopExpertAck(val templateId: String, val revision: Long, val digest: String) {
    companion object {
        fun forWorkspace(workspace: PolicyWorkspace, preferences: StoredState): DesktopExpertAck {
            val hash = MessageDigest.getInstance("SHA-256")
            // Bind the saved policy to its shared profile inventory and the inputs used by the Windows assembler.
            val defaults = preferences.engineDefaults(strict = EngineDefaults().strictRoute)
            listOf(Json.encodeToString(workspace.saved), Json.encodeToString(workspace.legacy), defaults.toString(), preferences.healthUrl)
                .forEach { input -> hash.update(input.toByteArray(Charsets.UTF_8)); hash.update(0) }
            return DesktopExpertAck(workspace.selectedTemplateId, workspace.saved.revision, HexFormat.of().formatHex(hash.digest()))
        }
    }
}

/**
 * Two small marks next to state.json:
 * - «session-open» lives while LerNET runs with the VPN switched on; finding it at start means LerNET itself
 *   crashed or was killed, and Windows removed the leak guard together with the process.
 * - «resume-vpn» says the user left the basic VPN on; «Отключить» or switching to Expert clears it. Autostart at sign-in uses it.
 * - «resume-expert» says the user left the Expert on; its own «Выключить» clears it. At sign-in it wins over
 *   «resume-vpn», the same order as Android after a reboot (BootRestoreDecision).
 */
class DesktopSessionMarks(private val directory: Path) {
    private val open get() = directory.resolve("session-open")
    private val resume get() = directory.resolve("resume-vpn")
    private val expert get() = directory.resolve("resume-expert")

    /** Read once at start, before this run writes its own mark. */
    val previousSessionCrashed: Boolean = Files.exists(directory.resolve("session-open"))

    val resumeWanted: Boolean get() = Files.exists(resume)

    /** Read once at start: the running Expert rewrites the mark as soon as it is on again. */
    val expertResumeWanted: Boolean = Files.exists(directory.resolve("resume-expert"))

    /** A process exit between the native change and its acknowledgement must never replay the old template. */
    @Synchronized
    internal fun invalidateExpert() {
        check(runCatching { Files.deleteIfExists(expert) }.isSuccess) {
            "Не удалось обновить запись автозапуска. Проверь доступ к папке настроек LerNET."
        }
    }

    /** Only a native-acknowledged policy can be automatically restarted at sign-in. */
    @Synchronized
    internal fun expertWanted(ack: DesktopExpertAck): Boolean =
        runCatching {
            Files.createDirectories(directory)
            val pending = directory.resolve("resume-expert.tmp")
            Files.writeString(pending, Json.encodeToString(ack))
            try {
                Files.move(pending, expert, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, expert, StandardCopyOption.REPLACE_EXISTING)
            }
        }.isSuccess

    internal fun restoreCandidate(editor: PolicyWorkspace, preferences: StoredState): Pair<DesktopExpertAck, PolicyWorkspace>? {
        val ack = runCatching { Json.decodeFromString<DesktopExpertAck>(Files.readString(expert)) }.getOrNull() ?: return null
        val candidate = editor.restartCandidate(ack.templateId, ack.revision) ?: return null
        return (ack to candidate).takeIf { ack == DesktopExpertAck.forWorkspace(candidate, preferences) }
    }

    /** The user switched the Expert off: it does not come back at the next sign-in. */
    @Synchronized
    fun expertStopped() {
        runCatching { Files.deleteIfExists(expert) }
    }

    fun vpnWanted() {
        runCatching {
            Files.createDirectories(directory)
            if (!Files.exists(open)) Files.writeString(open, "")
            if (!Files.exists(resume)) Files.writeString(resume, "")
        }
    }

    /** The user pressed «Отключить»: no crash notice and no reconnect at the next sign-in. */
    fun vpnStopped() {
        runCatching { Files.deleteIfExists(open) }
        runCatching { Files.deleteIfExists(resume) }
    }

    /** Normal exit with the VPN still on: no crash notice, but the VPN comes back at the next start. */
    fun closedCleanly() {
        runCatching { Files.deleteIfExists(open) }
    }

    companion object {
        const val CRASH_NOTICE =
            "Прошлый сеанс LerNET завершился аварийно, когда VPN был включён. После такого сбоя Windows снимает " +
                "защиту от утечек, поэтому трафик мог идти мимо VPN."
    }
}

/** What «Запускать при входе в систему» brings back; the Expert first, as on Android after a reboot. */
enum class SignInResume {
    NONE,
    EXPERT,
    VPN;

    companion object {
        fun decide(autostart: Boolean, expertWanted: Boolean, vpnWanted: Boolean): SignInResume = when {
            !autostart -> NONE
            expertWanted -> EXPERT
            vpnWanted -> VPN
            else -> NONE
        }
    }
}
