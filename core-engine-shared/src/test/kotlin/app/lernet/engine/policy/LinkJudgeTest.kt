package app.lernet.engine.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkJudgeTest {
    private fun decide(up: Boolean = true, failure: LinkFailure = LinkFailure.TUNNEL_DEAD, n: Int = 1, since: Long = 60_000, alt: Boolean = true) =
        LinkJudge.decide(up, failure, n, since, alt)

    @Test fun noNetworkAlwaysWaits() {
        LinkFailure.entries.forEach { assertEquals(LinkDecision.WAIT_FOR_NETWORK, decide(up = false, failure = it, n = 5)) }
    }

    @Test fun deadTunnelLooksForACheckedReplacement() {
        assertEquals(LinkDecision.SWITCH_NOW, decide(failure = LinkFailure.TUNNEL_DEAD))
    }

    @Test fun blockDetectorAloneNeverSwitchesOrRestarts() {
        assertEquals(LinkDecision.HOLD, decide(failure = LinkFailure.BLOCKED))
        assertEquals(LinkDecision.HOLD, decide(failure = LinkFailure.BLOCKED, n = 9, alt = false))
    }

    @Test fun engineErrorGetsOneRetryThenSwitches() {
        assertEquals(LinkDecision.RETRY_SAME, decide(failure = LinkFailure.ENGINE_ERROR, n = 1))
        assertEquals(LinkDecision.SWITCH_NOW, decide(failure = LinkFailure.ENGINE_ERROR, n = 2))
    }

    @Test fun freshNetworkSettlesBeforeBlamingTheConfig() {
        assertEquals(LinkDecision.HOLD, decide(since = 1_500))
        assertEquals(LinkDecision.RETRY_SAME, decide(failure = LinkFailure.ENGINE_ERROR, n = 3, since = 1_500))
        assertEquals(LinkDecision.SWITCH_NOW, decide(since = LinkJudge.SETTLE_MS))
    }

    @Test fun deadTunnelWithNothingToSwitchToHoldsInsteadOfRestarting() {
        assertEquals(LinkDecision.HOLD, decide(alt = false))
        assertEquals(LinkDecision.RETRY_SAME, decide(failure = LinkFailure.ENGINE_ERROR, n = 4, alt = false))
    }

    @Test fun holdTextsArePlainAndPolite() {
        val jammed = LinkJudge.holdMessage(LinkHold.JAMMED)
        assertTrue(jammed.startsWith("Связь глушат"))
        val silent = LinkJudge.holdMessage(LinkHold.ALL_SILENT, "2 мин")
        assertTrue(silent.contains("ни один сервер не отвечает"))
        assertTrue(silent.endsWith("проверим снова через 2 мин"))
        // Plain mode speaks «вы»: no «ты»-forms and no jargon.
        listOf(jammed, silent, LinkJudge.message(LinkDecision.SWITCH_NOW), LinkJudge.message(LinkDecision.RETRY_SAME)).forEach { text ->
            listOf(" ты ", "твой", "xmux", "handshake", "TLS", "SNI").forEach { word -> assertTrue("$word in $text", word !in text) }
        }
    }
}
