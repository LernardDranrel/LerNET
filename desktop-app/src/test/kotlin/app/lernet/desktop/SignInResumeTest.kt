package app.lernet.desktop

import app.lernet.config.policy.PolicyWorkspace
import app.lernet.config.policy.StoredPolicyTemplate
import app.lernet.config.transfer.TransferBundle
import app.lernet.routing.policy.NetworkPolicy
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class SignInResumeTest {
    private val general = PolicyWorkspace(
        legacy = TransferBundle(scope = "all", groups = emptyList(), profiles = emptyList(), rules = emptyList()),
        saved = NetworkPolicy(revision = 4),
    )
    private val preferences = StoredState()
    @Test
    fun `expert left on comes back first, as on android after a reboot`() {
        assertThat(SignInResume.decide(autostart = true, expertWanted = true, vpnWanted = true)).isEqualTo(SignInResume.EXPERT)
        assertThat(SignInResume.decide(autostart = true, expertWanted = false, vpnWanted = true)).isEqualTo(SignInResume.VPN)
        assertThat(SignInResume.decide(autostart = true, expertWanted = false, vpnWanted = false)).isEqualTo(SignInResume.NONE)
        // A start by hand never reconnects on its own.
        assertThat(SignInResume.decide(autostart = false, expertWanted = true, vpnWanted = true)).isEqualTo(SignInResume.NONE)
    }

    @Test
    fun `expert mark is read once at start and cleared by switching the expert off`() {
        val dir = Files.createTempDirectory("lernet-marks")
        DesktopSessionMarks(dir).expertWanted(DesktopExpertAck.forWorkspace(general, preferences))
        val next = DesktopSessionMarks(dir)
        assertThat(next.expertResumeWanted).isTrue()
        next.expertStopped()
        assertThat(next.expertResumeWanted).isTrue() // this run already decided
        assertThat(DesktopSessionMarks(dir).expertResumeWanted).isFalse()
    }

    @Test
    fun `resume uses acknowledged template when another template is selected`() {
        val marks = DesktopSessionMarks(Files.createTempDirectory("lernet-template-resume"))
        marks.expertWanted(DesktopExpertAck.forWorkspace(general, preferences))
        val work = general.copy(
            selectedTemplateId = "work", selectedTemplateName = "Рабочий", saved = NetworkPolicy(revision = 9),
            otherTemplates = listOf(StoredPolicyTemplate("general", "Общий", general.saved.copy(revision = 7))),
        )
        val candidate = marks.restoreCandidate(work, preferences)?.second
        assertThat(candidate?.selectedTemplateId).isEqualTo("general")
        assertThat(candidate?.saved).isEqualTo(general.saved)
        assertThat(work.selectedTemplateId).isEqualTo("work")
        assertThat(marks.restoreCandidate(work.copy(otherTemplates = emptyList()), preferences)).isNull()
        assertThat(marks.restoreCandidate(work, preferences.copy(tunMtu = 1400))).isNull()
        assertThat(marks.restoreCandidate(work.copy(legacy = work.legacy.copy(selectedProfileId = "changed")), preferences)).isNull()
        val replaced = work.copy(otherTemplates = listOf(StoredPolicyTemplate("general", "Общий", general.saved.copy(trees = listOf(general.saved.device)))))
        assertThat(marks.restoreCandidate(replaced, preferences)).isNull()
    }

    @Test
    fun `old boolean or malformed marker requires manual confirmation`() {
        val dir = Files.createTempDirectory("lernet-legacy-resume")
        for (raw in listOf("", "invalid")) {
            Files.writeString(dir.resolve("resume-expert"), raw)
            assertThat(DesktopSessionMarks(dir).restoreCandidate(general, preferences)).isNull()
        }
    }

    @Test
    fun `failed promotion cannot leave the previous template eligible for restart`() {
        val dir = Files.createTempDirectory("lernet-template-promotion")
        val marks = DesktopSessionMarks(dir)
        val ack = DesktopExpertAck.forWorkspace(general, preferences)
        assertThat(marks.expertWanted(ack)).isTrue()
        marks.invalidateExpert()
        Files.createDirectory(dir.resolve("resume-expert.tmp"))
        assertThat(marks.expertWanted(ack.copy(templateId = "work"))).isFalse()
        assertThat(DesktopSessionMarks(dir).expertResumeWanted).isFalse()
    }
}
