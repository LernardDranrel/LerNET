package app.lernet.engine.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelWatchTest {
    @Test
    fun probeMapsUpAndDown() {
        assertThat(ChannelWatch.afterProbe(wasUp = false, ok = false)).isEqualTo(ChannelHealth.HOP_DOWN)
        assertThat(ChannelWatch.afterProbe(wasUp = true, ok = false)).isEqualTo(ChannelHealth.HOP_LOST)
        assertThat(ChannelWatch.afterProbe(wasUp = false, ok = true)).isEqualTo(ChannelHealth.HOP_UP)
    }

    @Test
    fun quietWindowWithDeadlineIsPipeSilent() {
        val health = ChannelWatch.afterQuietWindow(hopUp = true, grew = false, sawDeadline = true)
        assertThat(health).isEqualTo(ChannelHealth.PIPE_SILENT)
    }

    @Test
    fun quietWindowWithPendingRequestsIsPipeSilent() {
        val health = ChannelWatch.afterQuietWindow(
            hopUp = true,
            grew = false,
            sawDeadline = false,
            requestsPending = true,
        )
        assertThat(health).isEqualTo(ChannelHealth.PIPE_SILENT)
    }

    @Test
    fun growthClearsToHopUp() {
        assertThat(ChannelWatch.afterQuietWindow(hopUp = true, grew = true, sawDeadline = false))
            .isEqualTo(ChannelHealth.HOP_UP)
    }

    @Test
    fun quietWithoutEvidenceWaits() {
        assertThat(ChannelWatch.afterQuietWindow(hopUp = true, grew = false, sawDeadline = false)).isNull()
    }

    @Test
    fun pipeSilentIsUnhealthy() {
        assertThat(ChannelWatch.isHonestlyUnhealthy(ChannelHealth.PIPE_SILENT)).isTrue()
        assertThat(ChannelWatch.isHonestlyUnhealthy(ChannelHealth.HOP_UP)).isFalse()
    }

    @Test
    fun allSilentTunnelRowsAreDead() {
        assertThat(ChannelWatch.classifySilence(listOf(true, true))).isEqualTo(ChannelHealth.TUNNEL_DEAD)
        assertThat(ChannelWatch.isHonestlyUnhealthy(ChannelHealth.TUNNEL_DEAD)).isTrue()
    }

    @Test
    fun mixedSilentTunnelRowsStayPartial() {
        assertThat(ChannelWatch.classifySilence(listOf(true, false))).isEqualTo(ChannelHealth.PIPE_SILENT)
    }

    @Test
    fun fullSilenceIsDeadCopyAndASubsetKeepsCounts() {
        assertThat(ChannelWatch.silenceReport(ChannelHealth.PIPE_SILENT, silent = 3, tunnel = 3))
            .isEqualTo(SilenceReport.ALL)
        assertThat(ChannelWatch.silenceReport(ChannelHealth.TUNNEL_DEAD, silent = 0, tunnel = 0))
            .isEqualTo(SilenceReport.ALL)
        assertThat(ChannelWatch.silenceReport(ChannelHealth.PIPE_SILENT, silent = 1, tunnel = 3))
            .isEqualTo(SilenceReport.PARTIAL)
        assertThat(ChannelWatch.silenceReport(ChannelHealth.PIPE_SILENT, silent = 0, tunnel = 0))
            .isEqualTo(SilenceReport.PARTIAL)
        assertThat(ChannelWatch.silenceReport(ChannelHealth.HOP_UP, silent = 0, tunnel = 0))
            .isEqualTo(SilenceReport.NONE)
    }

    @Test
    fun answeredTunnelRowsAreNotSilence() {
        assertThat(ChannelWatch.classifySilence(emptyList())).isNull()
        assertThat(ChannelWatch.classifySilence(listOf(false, false))).isNull()
    }
}
