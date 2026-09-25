package app.lernet.ui.status

import app.lernet.engine.net.HopChip
import app.lernet.engine.net.HopPath
import app.lernet.engine.net.HopSilence
import app.lernet.engine.net.HopStop
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HopPathLineTest {
    @Test
    fun stripShowsLocalThenFlagsWithHonestTimeoutGaps() {
        val chips = HopPath.chips(
            listOf(
                HopStop("10.0.0.1", country = "ru", timedOut = false),
                HopStop(null, country = null, timedOut = true),
                HopStop("203.0.113.42", country = "de", timedOut = false),
            ),
        )
        assertThat(chips).containsExactly(
            HopChip.Phone,
            HopChip.Country("RU"),
            HopChip.Timeout,
            HopChip.Country("DE"),
        ).inOrder()
    }

    @Test
    fun awaitingChipAppearsWhileProbeRunsEvenWithEmptyHops() {
        assertThat(HopPath.chips(emptyList(), awaiting = true)).containsExactly(
            HopChip.Phone,
            HopChip.Awaiting,
        ).inOrder()
    }

    @Test
    fun cardsDoNotInventOrgOrCountryLabels() {
        val stops = listOf(
            HopStop("10.0.0.1", country = "de", timedOut = false, rttMs = 24, name = "core1.atlas.example"),
            HopStop(null, country = null, timedOut = true),
            HopStop("203.0.113.5", country = "МГУ", timedOut = false),
        )
        val cards = HopPath.cards(stops)
        assertThat(cards[0].title).isEqualTo("core1.atlas.example")
        assertThat(cards[0].country).isEqualTo("DE")
        assertThat(cards[1].timedOut).isTrue()
        assertThat(cards[1].title).isNull()
        assertThat(cards[2].country).isNull()
        assertThat(cards[2].title).isNull()
        assertThat(HopPath.silence(stops, channelUp = true)).isEqualTo(HopSilence.PARTIAL)
    }
}
