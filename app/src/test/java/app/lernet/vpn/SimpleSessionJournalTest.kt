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
    private fun config() = SimpleSessionConfig(
        "profile", "outbound", "{\"secret\":\"effective\"}",
        RunMode.FULL_VPN, "warn", EngineDefaults(1400, 1, "9.9.9.9"), "proxy", DnsPolicy.UNDERLAY
    )

    @Test fun restartReadsExactEffectiveSessionAndManualStopPersistsAcrossNewInstance() {
        val path = folder.root.toPath().resolve("session.json")
        SimpleSessionJournal(path).write(config())
        assertEquals(config(), SimpleSessionJournal(path).read()?.config())
        SimpleSessionJournal(path).write(null)
        assertNull(SimpleSessionJournal(path).read())
    }

    @Test fun restoredSessionKeepsBypassedAppsAndTlsSettingsForTheNextFailover() {
        val path = folder.root.toPath().resolve("session.json")
        val full = config().copy(
            defaults = EngineDefaults(1400, 1, "9.9.9.9", tlsRecordFragment = true, realityKeyShare = "classical",
                bypassApps = listOf("com.bank", "org.mail")),
        )
        SimpleSessionJournal(path).write(full)
        assertEquals(full.defaults, SimpleSessionJournal(path).read()!!.config().defaults)
    }

    @Test fun journalFromAnOlderBuildStillReads() {
        val path = folder.root.toPath().resolve("session.json")
        SimpleSessionJournal(path).write(config())
        val old = String(Files.readAllBytes(path)).replace(Regex(""",\"(tlsRecordFragment|realityKeyShare|bypassApps)\":[^,}]*"""), "")
        Files.write(path, old.toByteArray())
        assertEquals(config(), SimpleSessionJournal(path).read()!!.config())
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
