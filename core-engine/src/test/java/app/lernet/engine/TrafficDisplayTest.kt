package app.lernet.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Home used to render only instantaneous B/s. CommandStatus often reports
 * 0 B/s while totals grow (user log: upTotal=10861 downTotal=13202).
 * That must not look like a dead path.
 */
class TrafficDisplayTest {
    @Test
    fun zeroRateWithGrowingTotalsIsNotDead() {
        val view = TrafficDisplay.format(
            uplinkBps = 0,
            downlinkBps = 0,
            uplinkTotal = 10_861,
            downlinkTotal = 13_202,
            dnsOk = false,
        )
        assertThat(view.impliesDead).isFalse()
        assertThat(view.showTotals).isTrue()
        assertThat(view.uplinkRate).isEqualTo("0 B/s")
        assertThat(view.downlinkRate).isEqualTo("0 B/s")
        assertThat(view.uplinkTotal).isNotEqualTo("0 B")
        assertThat(view.downlinkTotal).isNotEqualTo("0 B")
        assertThat(view.primary).isNotEqualTo("↑ 0 B/s ↓ 0 B/s")
        assertThat(view.primary).contains(view.uplinkTotal)
        assertThat(view.primary).contains(view.downlinkTotal)
    }

    @Test
    fun zeroEverythingWithoutDnsLooksDead() {
        val view = TrafficDisplay.format(
            uplinkBps = 0,
            downlinkBps = 0,
            uplinkTotal = 0,
            downlinkTotal = 0,
            dnsOk = false,
        )
        assertThat(view.impliesDead).isTrue()
        assertThat(view.showTotals).isFalse()
        assertThat(view.dnsHint).isFalse()
        assertThat(view.primary).isEqualTo("↑ 0 B/s ↓ 0 B/s")
    }

    @Test
    fun zeroBytesButDnsOkIsNotDead() {
        val view = TrafficDisplay.format(
            uplinkBps = 0,
            downlinkBps = 0,
            uplinkTotal = 0,
            downlinkTotal = 0,
            dnsOk = true,
        )
        assertThat(view.impliesDead).isFalse()
        assertThat(view.dnsHint).isTrue()
        assertThat(view.primary).isNotEqualTo("↑ 0 B/s ↓ 0 B/s")
    }

    @Test
    fun liveRateDoesNotNeedTotals() {
        val view = TrafficDisplay.format(
            uplinkBps = 2048,
            downlinkBps = 4096,
            uplinkTotal = 100,
            downlinkTotal = 200,
            dnsOk = true,
        )
        assertThat(view.impliesDead).isFalse()
        assertThat(view.uplinkRate).isEqualTo("2 KiB/s")
        assertThat(view.downlinkRate).isEqualTo("4 KiB/s")
        assertThat(view.showTotals).isTrue()
    }
}
