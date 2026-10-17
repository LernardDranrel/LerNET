package app.lernet.engine.policy

import app.lernet.routing.policy.NetworkPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpertUptimeTest {
    @Test
    fun formatsDaysHoursAndMinutes() {
        assertThat(ExpertUptime.format(30_000)).isEqualTo("меньше минуты")
        assertThat(ExpertUptime.format(5 * 60_000L)).isEqualTo("5 мин")
        assertThat(ExpertUptime.format((3 * 60 + 7) * 60_000L)).isEqualTo("3 ч 07 мин")
        assertThat(ExpertUptime.format(((14 * 24 + 6) * 60 + 59) * 60_000L)).isEqualTo("14 дн 06 ч")
    }

    @Test
    fun showsNothingWithoutARunningTunnelOrStartTime() {
        val base = ExpertRuntimeState(saved = NetworkPolicy())
        assertThat(ExpertUptime.label(base.copy(phase = ExpertSessionPhase.RUNNING), 1_000)).isNull()
        assertThat(ExpertUptime.label(base.copy(phase = ExpertSessionPhase.STOPPED, runningSinceEpochMs = 0), 1_000)).isNull()
        // Still checking the link: no counter next to «проверяем связь».
        assertThat(ExpertUptime.label(base.copy(phase = ExpertSessionPhase.RUNNING, runningSinceEpochMs = 0), 120_000)).isNull()
        val confirmed = base.copy(phase = ExpertSessionPhase.RUNNING, tun = TunIdentity("tun"), appliedRevision = 1)
        assertThat(ExpertUptime.label(confirmed.copy(runningSinceEpochMs = 0), 120_000)).isEqualTo("2 мин")
        assertThat(ExpertUptime.label(confirmed.copy(runningSinceEpochMs = 5_000), 1_000)).isEqualTo("меньше минуты")
    }
}
