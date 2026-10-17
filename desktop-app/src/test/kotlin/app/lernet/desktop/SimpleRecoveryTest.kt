package app.lernet.desktop

import com.google.common.truth.Truth.assertThat
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
    fun quickTriesFirstThenRandomRecheckUpTo5Minutes() {
        repeat(300) {
            for (attempt in 1..SimpleRecovery.QUICK_ATTEMPTS) {
                val upper = SimpleRecovery.FIRST_PAUSE_MS shl (attempt - 1)
                val pause = SimpleRecovery.pauseMs(attempt)
                assertThat(pause).isAtLeast(upper / 2)
                assertThat(pause).isAtMost(upper)
            }
            val recheck = SimpleRecovery.pauseMs(SimpleRecovery.QUICK_ATTEMPTS + 1 + it % 20)
            assertThat(recheck).isAtLeast(45_000L)
            assertThat(recheck).isAtMost(300_000L)
            assertThat(recheck % 1_000).isEqualTo(0L)
        }
    }

    @Test
    fun pausesAreNotAFixedRhythm() {
        val pauses = List(40) { SimpleRecovery.pauseMs(10) }
        assertThat(pauses.toSet().size).isGreaterThan(20)
        assertThat(List(40) { SimpleRecovery.pauseMs(1) }.toSet().size).isGreaterThan(20)
    }

    @Test
    fun messageSaysWhetherTheNetworkIsClosed() {
        assertThat(SimpleRecovery.message(true, 7_200, "check failed"))
            .isEqualTo("VPN включён · сеть закрыта, в обход ничего не уходит · повтор через 8 с · check failed")
        assertThat(SimpleRecovery.message(false, 1_000, "")).isEqualTo("VPN не работает · повтор через 1 с")
        assertThat(SimpleRecovery.message(true, 292_000, "")).isEqualTo("VPN включён · сеть закрыта, в обход ничего не уходит · повтор через 4:52")
    }

    @Test
    fun failedFolderConfigsComeBackAfterTheRandomPause() {
        val recheck = UnavailableRecheck { 187_000 }
        assertThat(recheck.consumeIfDue(0)).isFalse()
        assertThat(recheck.arm(1_000)).isEqualTo(187_000L)
        // Arming again does not push the recheck further out.
        assertThat(recheck.arm(61_000)).isEqualTo(127_000L)
        assertThat(recheck.consumeIfDue(187_999)).isFalse()
        assertThat(recheck.consumeIfDue(188_000)).isTrue()
        assertThat(recheck.consumeIfDue(400_000)).isFalse()
    }

    @Test
    fun defaultFolderRecheckIsRandomWithinBounds() {
        val waits = List(200) { UnavailableRecheck().arm(0) }
        assertThat(waits.all { it in 45_000L..300_000L && it % 1_000 == 0L }).isTrue()
        assertThat(waits.toSet().size).isGreaterThan(60)
    }
}
