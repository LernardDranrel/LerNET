package app.lernet.vpn

import app.lernet.config.model.DnsPolicy
import app.lernet.engine.RunMode
import app.lernet.engine.SimpleSessionConfig
import app.lernet.engine.compile.EngineDefaults
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SimpleSessionJournalTest {
    @get:Rule val folder = TemporaryFolder()
    private fun config() = SimpleSessionConfig("profile", "outbound", "{\"secret\":\"effective\"}",
        RunMode.FULL_VPN, "warn", EngineDefaults(1400, "8-16", "9.9.9.9"), "proxy", DnsPolicy.UNDERLAY)

    @Test fun restartReadsExactEffectiveSessionAndManualStopPersistsAcrossNewInstance() {
        val path = folder.root.toPath().resolve("session.json")
        SimpleSessionJournal(path).write(config())
        assertEquals(config(), SimpleSessionJournal(path).read()?.config())
        SimpleSessionJournal(path).write(null)
        assertNull(SimpleSessionJournal(path).read())
    }

    @Test fun proxyModeDoesNotBecomeAnAlwaysOnVpnAndNewStartInvalidatesOldGeneration() {
        val journal = SimpleSessionJournal(folder.root.toPath().resolve("session.json"))
        journal.write(config())
        val before = journal.read()!!.generation
        journal.write(config())
        assertNotEquals(before, journal.read()!!.generation)
        journal.write(config().copy(mode = RunMode.PROXY))
        assertNull(journal.read())
    }

    @Test fun unfinishedWriteDoesNotReplaceLastCommittedIntent() {
        val path = folder.root.toPath().resolve("session.json")
        val journal = SimpleSessionJournal(path)
        journal.write(config())
        Files.write(path.resolveSibling("session.json.tmp"), "broken".toByteArray())
        assertEquals(config(), SimpleSessionJournal(path).read()!!.config())
    }

    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun corruptIntentCannotSilentlyRestoreAnArbitraryProfile() {
        val path = folder.root.toPath().resolve("session.json")
        Files.write(path, "broken".toByteArray())
        SimpleSessionJournal(path).read()
    }
}
