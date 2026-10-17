package app.lernet.desktop

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class SimpleRecoveryTest {
    @Test
    fun onlyTunPermissionGivesTheNetworkBack() {
        assertThat(SimpleRecovery.releasesGuard(TunnelFailure.TUN_PERMISSION)).isTrue()
        // Preflight error, core check failure, failed failover start: network stays closed.
        assertThat(SimpleRecovery.releasesGuard(null)).isFalse()
    }

    @Test
    fun failedTunnelIsRetriedWhileWanted() {
        assertThat(SimpleRecovery.retries(desired = true, failure = null, swapped = false)).isTrue()
        assertThat(SimpleRecovery.retries(desired = false, failure = null, swapped = false)).isFalse()
        assertThat(SimpleRecovery.retries(desired = true, failure = null, swapped = true)).isFalse()
        assertThat(SimpleRecovery.retries(desired = true, failure = TunnelFailure.TUN_PERMISSION, swapped = false)).isFalse()
    }

    @Test
    fun pauseGrowsIsCappedAndNeverZero() {
        val random = Random(7)
        repeat(200) {
            val attempt = it % 12 + 1
            val pause = SimpleRecovery.pauseMs(attempt, random)
            val upper = (SimpleRecovery.FIRST_PAUSE_MS shl (attempt - 1).coerceAtMost(8)).coerceAtMost(SimpleRecovery.MAX_PAUSE_MS)
            assertThat(pause).isAtLeast(upper / 2)
            assertThat(pause).isAtMost(upper)
        }
        assertThat(SimpleRecovery.pauseMs(1, Random(1))).isAtLeast(2_500L)
        assertThat(SimpleRecovery.pauseMs(50, Random(1))).isAtMost(SimpleRecovery.MAX_PAUSE_MS)
    }

    @Test
    fun pausesAreNotAFixedRhythm() {
        val pauses = List(20) { SimpleRecovery.pauseMs(10) }.toSet()
        assertThat(pauses.size).isGreaterThan(10)
    }

    @Test
    fun messageSaysWhetherTheNetworkIsClosed() {
        assertThat(SimpleRecovery.message(true, 7_200, "check failed"))
            .isEqualTo("VPN включён · сеть закрыта, в обход ничего не уходит · повтор через 8 с · check failed")
        assertThat(SimpleRecovery.message(false, 1_000, "")).isEqualTo("VPN не работает · повтор через 1 с")
    }
}
