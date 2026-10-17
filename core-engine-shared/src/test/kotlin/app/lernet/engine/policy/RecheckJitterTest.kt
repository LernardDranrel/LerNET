package app.lernet.engine.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecheckJitterTest {
    @Test
    fun staysWithin45SecondsAnd5MinutesInWholeSeconds() {
        repeat(10_000) {
            val ms = RecheckJitter.nextMs()
            assertThat(ms).isAtLeast(45_000L)
            assertThat(ms).isAtMost(300_000L)
            assertThat(ms % 1_000).isEqualTo(0L)
        }
    }

    @Test
    fun intervalsDifferAndCoverTheRange() {
        val draws = List(500) { RecheckJitter.nextMs() }
        assertThat(draws.toSet().size).isGreaterThan(150)
        assertThat(draws.min()).isLessThan(90_000L)
        assertThat(draws.max()).isGreaterThan(255_000L)
        // Consecutive rechecks are not the same pause over and over.
        assertThat(draws.zipWithNext().count { (a, b) -> a != b }).isGreaterThan(450)
    }

    @Test
    fun respectsALongerUserCooldownButNeverExceeds5Minutes() {
        repeat(2_000) {
            val ms = RecheckJitter.nextMs(atLeastMs = 120_500)
            assertThat(ms).isAtLeast(121_000L)
            assertThat(ms).isAtMost(300_000L)
        }
        assertThat(RecheckJitter.nextMs(atLeastMs = 900_000)).isEqualTo(300_000L)
        assertThat(RecheckJitter.nextMs(atLeastMs = 0)).isAtLeast(45_000L)
    }

    @Test
    fun labelsLikeAClock() {
        assertThat(RecheckJitter.label(60_000)).isEqualTo("1:00")
        assertThat(RecheckJitter.label(292_000)).isEqualTo("4:52")
        assertThat(RecheckJitter.label(187_000)).isEqualTo("3:07")
    }
}
