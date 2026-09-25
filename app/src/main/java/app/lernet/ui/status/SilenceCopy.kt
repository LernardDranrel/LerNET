package app.lernet.ui.status

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lernet.R
import app.lernet.engine.live.ChannelHealth
import app.lernet.engine.live.ChannelWatch
import app.lernet.engine.live.SilenceReport

@Composable
fun silenceTitle(channel: ChannelHealth, silent: Int, tunnel: Int): String =
    when (ChannelWatch.silenceReport(channel, silent, tunnel)) {
        SilenceReport.ALL -> stringResource(R.string.channel_tunnel_dead)
        SilenceReport.PARTIAL -> pipeCounts(silent, tunnel)
        SilenceReport.NONE -> stringResource(R.string.channel_pipe_silent)
    }

@Composable
fun silenceBanner(channel: ChannelHealth, silent: Int, tunnel: Int): String =
    when (ChannelWatch.silenceReport(channel, silent, tunnel)) {
        SilenceReport.ALL -> stringResource(R.string.tunnel_dead_banner)
        SilenceReport.PARTIAL -> bannerCounts(silent, tunnel)
        SilenceReport.NONE -> stringResource(R.string.pipe_silent_banner)
    }

@Composable
private fun pipeCounts(silent: Int, tunnel: Int): String =
    counted(silent, tunnel, R.string.channel_pipe_silent_counts, R.string.channel_pipe_silent)

@Composable
private fun bannerCounts(silent: Int, tunnel: Int): String =
    counted(silent, tunnel, R.string.pipe_silent_banner_counts, R.string.pipe_silent_banner)

@Composable
private fun counted(silent: Int, tunnel: Int, @StringRes counts: Int, @StringRes plain: Int): String =
    if (tunnel > 0 && silent in 1 until tunnel) {
        stringResource(counts, silent, tunnel)
    } else {
        stringResource(plain)
    }
