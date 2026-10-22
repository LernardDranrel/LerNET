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
    fun quickTriesUseTheSharedBackoffThenRandomRecheckUpTo5Minutes() {
        repeat(300) {
            // Same pauses as Android: 5, 15, 30, 30 s (each up to a quarter longer, capped at 30 s), never 1–2 s.
            val steps = listOf(5_000L..6_250L, 15_000L..18_750L, 30_000L..30_000L, 30_000L..30_000L)
            for (attempt in 1..SimpleRecovery.QUICK_ATTEMPTS) {
                assertThat(SimpleRecovery.pauseMs(attempt)).isIn(
                    com.google.common.collect.Range.closed(
                        steps[attempt - 1].first,
                        steps[
                            attempt -
                                1
                        ].last
                    )
                )
            }
            val recheck = SimpleRecovery.pauseMs(SimpleRecovery.QUICK_ATTEMPTS + 1 + it % 20)
            assertThat(recheck).isAtLeast(45_000L)
            assertThat(recheck).isAtMost(300_000L)
            assertThat(recheck % 1_000).isEqualTo(0L)
        }
    }

    @Test
    fun silentRoundsAndTheHandshakeBudgetStretchThePause() {
        assertThat(SimpleRecovery.pauseMs(1, budgetWaitMs = 52_000L)).isEqualTo(52_000L)
        val rare = SimpleRecovery.pauseMs(1, silentRounds = SimpleRecovery.ALL_SILENT_ROUNDS)
        assertThat(rare).isIn(com.google.common.collect.Range.closed(4 * 60_000L, 6 * 60_000L))
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
    fun failoverSearchDoesNotBlockTheCallerAndRunsOneAtATime() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val runner = FailoverRunner()
        val started = System.nanoTime()
        assertThat(runner.launch { gate.await(); done.countDown() }).isTrue()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(500L)
        assertThat(runner.isRunning).isTrue()
        assertThat(runner.launch { error("must not run") }).isFalse()
        gate.countDown()
        assertThat(done.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        repeat(50) { if (runner.isRunning) Thread.sleep(10) }
        assertThat(runner.isRunning).isFalse()
        assertThat(runner.launch { }).isTrue()
    }
}
