package app.lernet.engine.expert

import app.lernet.config.policy.PolicyMigration
import app.lernet.config.policy.PolicyWorkspace
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.compile.EngineDefaults
import app.lernet.routing.policy.PolicyTarget
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExpertRestartJournalTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun normalRuleSetCatalogueLargerThanPackaged238FilesCanBeFingerprinted() {
        val directory = temporary.newFolder("rules")
        repeat(300) { index -> File(directory, "geo-$index.srs").writeBytes(byteArrayOf(index.toByte(), 1, 2)) }
        File(directory, "README.txt").writeText("Not a compiled rule set")
        val first = ExpertRestartFingerprint.ruleSetDigests(directory.absolutePath)
        assertThat(first).hasSize(300)
        assertThat(ExpertRestartFingerprint.ruleSetDigests(directory.absolutePath)).isEqualTo(first)
        File(directory, "geo-299.srs").writeBytes(byteArrayOf(9, 9, 9))
        val changed = ExpertRestartFingerprint.ruleSetDigests(directory.absolutePath)
        assertThat(changed["geo-299.srs"]).isNotEqualTo(first["geo-299.srs"])
        assertThat(changed["geo-0.srs"]).isEqualTo(first["geo-0.srs"])
    }

    @Test
    fun ruleSetCatalogueRetainsCountAggregateAndIndividualFileBudgets() {
        ExpertRestartFingerprint.validateRuleSetBudget(List(300) { 5_000L })
        assertThrows(IllegalArgumentException::class.java) {
            ExpertRestartFingerprint.validateRuleSetBudget(List(4_097) { 0L })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpertRestartFingerprint.validateRuleSetBudget(listOf(64_000_001L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpertRestartFingerprint.validateRuleSetBudget(List(5) { 64_000_000L })
        }
    }

    @Test
    fun matchingAcknowledgedInputsSurviveProcessRestart() {
        val storage = MemoryStorage()
        val journal = ExpertRestartJournal(storage)
        val fingerprint = fingerprint(source())
        journal.invalidate()
        assertThat(journal.promoteActualAck(fingerprint, 4)).isTrue()
        assertThat(ExpertRestartJournal(storage).matches(fingerprint)).isTrue()
        assertThat(storage.raw).doesNotContain("private-credential")
    }

    @Test
    fun savedButUnappliedPolicyCannotRestoreAutomatically() {
        val workspace = source()
        val changed = workspace.copy(saved = workspace.saved.copy(device = workspace.saved.device.copy(defaultTarget = PolicyTarget.Block)))
        assertThat(confirmed(workspace).matches(fingerprint(changed))).isFalse()
    }

    @Test
    fun profileCredentialsDnsAndFolderInventoryBelongToActualAppliedInputs() {
        val workspace = source()
        val journal = confirmed(workspace)
        val profile = workspace.legacy.profiles.single()
        val changedProfile = profile.copy(
            outbounds = profile.outbounds.map { it.copy(singBoxJson = it.singBoxJson.replace("private-credential", "rotated-credential")) },
        )
        val changedCredentials = workspace.copy(legacy = workspace.legacy.copy(profiles = listOf(changedProfile)))
        assertThat(journal.matches(fingerprint(changedCredentials))).isFalse()
        val changedDns = profile.copy(dnsPolicy = "PROFILE")
        assertThat(journal.matches(fingerprint(workspace.copy(legacy = workspace.legacy.copy(profiles = listOf(changedDns)))))).isFalse()
        val changedFolder = workspace.legacy.groups.single().copy(autoSwap = true)
        assertThat(journal.matches(fingerprint(workspace.copy(legacy = workspace.legacy.copy(groups = listOf(changedFolder)))))).isFalse()
    }

    @Test
    fun changedEngineDefaultsAndCompiledInputsCannotUsePreviousAck() {
        val workspace = source()
        val journal = confirmed(workspace)
        assertThat(journal.matches(fingerprint(workspace, EngineDefaults(directDnsServer = "9.9.9.9")))).isFalse()
        assertThat(journal.matches(ExpertRestartFingerprint.calculate(workspace, EngineDefaults(), listOf("new-core")))).isFalse()
        assertThat(journal.matches(ExpertRestartFingerprint.calculate(workspace, EngineDefaults(), INPUTS, mapOf("geo.srs" to "new"))))
            .isFalse()
    }

    @Test
    fun draftOnlyChangesDoNotBlockMatchingRestoration() {
        val workspace = source()
        val draft = workspace.saved.copy(device = workspace.saved.device.copy(defaultTarget = PolicyTarget.Block))
        assertThat(confirmed(workspace).matches(fingerprint(workspace.copy(draft = draft)))).isTrue()
    }

    @Test
    fun failedDurablePromotionKeepsRestorationIneligibleAcrossProcessRestart() {
        val storage = MemoryStorage()
        val journal = ExpertRestartJournal(storage)
        val fingerprint = fingerprint(source())
        journal.invalidate()
        storage.failWrites = true
        assertThat(journal.promoteActualAck(fingerprint, 9)).isFalse()
        assertThat(journal.matches(fingerprint)).isFalse()
        assertThat(ExpertRestartJournal(storage).matches(fingerprint)).isFalse()
    }

    @Test
    fun failedInvalidationMustPreventNativeMutation() {
        val storage = MemoryStorage()
        val journal = ExpertRestartJournal(storage)
        val fingerprint = fingerprint(source())
        journal.invalidate()
        journal.promoteActualAck(fingerprint, 1)
        storage.failWrites = true
        var nativeMutated = false
        assertThrows(IllegalStateException::class.java) {
            journal.invalidate()
            nativeMutated = true
        }
        assertThat(nativeMutated).isFalse()
        assertThat(journal.matches(fingerprint)).isFalse()
    }

    @Test
    fun missingMalformedOrQuotedJournalCannotAuthorizeRestoration() {
        val storage = MemoryStorage()
        val fingerprint = fingerprint(source())
        assertThat(ExpertRestartJournal(storage).matches(fingerprint)).isFalse()
        storage.raw = """{"version":1,"eligible":"true","fingerprint":"$fingerprint","revision":1}"""
        assertThat(ExpertRestartJournal(storage).matches(fingerprint)).isFalse()
        storage.raw = """{"version":{},"eligible":true,"fingerprint":"$fingerprint","revision":1}"""
        assertThat(ExpertRestartJournal(storage).matches(fingerprint)).isFalse()
    }

    private fun confirmed(workspace: PolicyWorkspace): ExpertRestartJournal {
        val storage = MemoryStorage()
        ExpertRestartJournal(storage).also {
            it.invalidate()
            check(it.promoteActualAck(fingerprint(workspace), workspace.saved.revision))
        }
        return ExpertRestartJournal(storage)
    }

    private fun fingerprint(workspace: PolicyWorkspace, defaults: EngineDefaults = EngineDefaults()): String =
        ExpertRestartFingerprint.calculate(workspace, defaults, INPUTS)

    private fun source(): PolicyWorkspace = PolicyMigration.migrate(
        TransferBundle(
            scope = "all",
            groups = listOf(TransferGroup("folder", "Germany", listOf("profile"))),
            profiles = listOf(
                TransferProfile(
                    "profile", "Germany", "JSON",
                    listOf(
                        TransferOutbound(
                            "outbound", "proxy", "socks",
                            """{"type":"socks","server":"proxy.test","password":"private-credential"}"""
                        )
                    ),
                    "outbound",
                ),
            ),
            rules = emptyList(),
        ),
    )

    private class MemoryStorage : ExpertRestartStorage {
        var raw: String? = null
        var failWrites = false
        override fun read(): String? = raw
        override fun write(raw: String) {
            check(!failWrites) { "simulated disk failure" }
            this.raw = raw
        }
    }

    companion object {
        private val INPUTS = listOf("core-1", "android-36", "compiled-ingress", "compiled-policy", "compiled-exit-manifest")
    }
}
