package app.lernet.desktop

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class SignInResumeTest {
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
        DesktopSessionMarks(dir).expertWanted()
        val next = DesktopSessionMarks(dir)
        assertThat(next.expertResumeWanted).isTrue()
        next.expertStopped()
        assertThat(next.expertResumeWanted).isTrue() // this run already decided
        assertThat(DesktopSessionMarks(dir).expertResumeWanted).isFalse()
    }
}
