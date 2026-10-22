package app.lernet.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Two small marks next to state.json:
 * - «session-open» lives while LerNET runs with the VPN switched on; finding it at start means LerNET itself
 *   crashed or was killed, and Windows removed the leak guard together with the process.
 * - «resume-vpn» says the user left the basic VPN on; «Отключить» or switching to Expert clears it. Autostart at sign-in uses it.
 */
class DesktopSessionMarks(private val directory: Path) {
    private val open get() = directory.resolve("session-open")
    private val resume get() = directory.resolve("resume-vpn")

    /** Read once at start, before this run writes its own mark. */
    val previousSessionCrashed: Boolean = Files.exists(directory.resolve("session-open"))

    val resumeWanted: Boolean get() = Files.exists(resume)

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
