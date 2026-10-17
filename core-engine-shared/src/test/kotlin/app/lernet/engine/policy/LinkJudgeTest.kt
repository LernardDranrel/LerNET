package app.lernet.engine.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkJudgeTest {
    private fun decide(up: Boolean = true, failure: LinkFailure = LinkFailure.TUNNEL_DEAD, n: Int = 1, since: Long = 60_000, alt: Boolean = true) =
        LinkJudge.decide(up, failure, n, since, alt)

    @Test fun noNetworkAlwaysWaits() {
        LinkFailure.entries.forEach { assertEquals(LinkDecision.WAIT_FOR_NETWORK, decide(up = false, failure = it, n = 5)) }
    }

    @Test fun deadOrBlockedConfigSwitchesAtOnce() {
        assertEquals(LinkDecision.SWITCH_NOW, decide(failure = LinkFailure.TUNNEL_DEAD))
        assertEquals(LinkDecision.SWITCH_NOW, decide(failure = LinkFailure.BLOCKED))
    }

    @Test fun engineErrorGetsOneRetryThenSwitches() {
        assertEquals(LinkDecision.RETRY_SAME, decide(failure = LinkFailure.ENGINE_ERROR, n = 1))
        assertEquals(LinkDecision.SWITCH_NOW, decide(failure = LinkFailure.ENGINE_ERROR, n = 2))
    }

    @Test fun freshNetworkSettlesBeforeBlamingTheConfig() {
        assertEquals(LinkDecision.RETRY_SAME, decide(since = 1_500))
        assertEquals(LinkDecision.SWITCH_NOW, decide(since = LinkJudge.SETTLE_MS))
    }

    @Test fun nothingToSwitchToRetriesSame() {
        assertEquals(LinkDecision.RETRY_SAME, decide(alt = false))
    }
}
