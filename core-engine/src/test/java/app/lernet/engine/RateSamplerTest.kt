package app.lernet.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RateSamplerTest {
    @Test
    fun zeroLibboxRateUsesTotalDelta() {
        val sampler = RateSampler()
        val first = sampler.sample(0, 0, uplinkTotal = 3_537, downlinkTotal = 1_950, nowMs = 1_000)
        assertThat(first.uplinkBps).isEqualTo(0L)
        assertThat(first.downlinkBps).isEqualTo(0L)

        val second = sampler.sample(0, 0, uplinkTotal = 5_570, downlinkTotal = 1_950, nowMs = 6_000)
        assertThat(second.uplinkBps).isEqualTo(406L)
        assertThat(second.downlinkBps).isEqualTo(0L)
    }

    @Test
    fun tinyGrowthIsAtLeastOneBytePerSecond() {
        val sampler = RateSampler()
        sampler.sample(0, 0, uplinkTotal = 100, downlinkTotal = 0, nowMs = 0)
        val next = sampler.sample(0, 0, uplinkTotal = 101, downlinkTotal = 0, nowMs = 5_000)
        assertThat(next.uplinkBps).isEqualTo(1L)
        assertThat(next.downlinkBps).isEqualTo(0L)
    }

    @Test
    fun reportedRateWinsOverTheDelta() {
        val sampler = RateSampler()
        sampler.sample(0, 0, uplinkTotal = 0, downlinkTotal = 0, nowMs = 0)
        val next = sampler.sample(2_048, 0, uplinkTotal = 10, downlinkTotal = 0, nowMs = 1_000)
        assertThat(next.uplinkBps).isEqualTo(2_048L)
    }

    @Test
    fun resetDropsThePreviousSample() {
        val sampler = RateSampler()
        sampler.sample(0, 0, uplinkTotal = 1_000, downlinkTotal = 1_000, nowMs = 0)
        sampler.reset()
        val next = sampler.sample(0, 0, uplinkTotal = 2_000, downlinkTotal = 2_000, nowMs = 1_000)
        assertThat(next.uplinkBps).isEqualTo(0L)
        assertThat(next.downlinkBps).isEqualTo(0L)
    }
}
