package app.lernet.config.redact

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

class DiagnosticJournalTest {
    private val journal = DiagnosticJournal()

    @Test fun warnHidesAddressThatInfoKeeps() {
        val fact = DiagFact(DiagEvent.DNS_DECISION, DiagKind.EDGE, domain = "dns.example", ip = "203.0.113.5")
        val warn = journal.admit(DiagLevel.WARN, fact, nowMs = 1_000)!!
        val info = DiagnosticJournal().admit(DiagLevel.INFO, fact, nowMs = 1_000)!!
        assertThat(warn).doesNotContain("dns.example")
        assertThat(warn).doesNotContain("203.0.113.5")
        assertThat(info).contains("domain=dns.example")
        assertThat(info).contains("ip=203.0.113.5")
    }

    @Test fun secretsStayOutAtEveryLevelIncludingExport() {
        val raw = """"password":"s3cret" "subscriptionUrl":"https://updates.example/sub/private-token" vless://abc@h:1"""
        listOf(DiagLevel.WARN, DiagLevel.INFO, DiagLevel.DEBUG).forEach { level ->
            val line = journal.text(level, raw, keepAddress = true, nowMs = 5)
            assertThat(line).doesNotContain("s3cret")
            assertThat(line).doesNotContain("private-token")
            assertThat(line).doesNotContain("vless://abc")
        }
        val exported = SecretRedactor.redact(journal.text(DiagLevel.INFO, "domain=dns.example $raw", keepAddress = true, nowMs = 5))
        assertThat(exported).contains("domain=dns.example")
        assertThat(exported).doesNotContain("private-token")
        assertThat(exported).doesNotContain("s3cret")
    }

    @Test fun explicitCheckKeepsAddressAtWarn() {
        val line = journal.text(DiagLevel.WARN, "event=hop_check dest=203.0.113.9", keepAddress = true, nowMs = 8)
        assertThat(line).contains("203.0.113.9")
        assertThat(line).contains("hop_check")
    }

    @Test fun repeatedStateIsHeldUntilTheSameGroupCloses() {
        val state = DiagFact(DiagEvent.HEALTH_GATE, DiagKind.STATE, outcome = "below", session = "7", attempt = 1)
        assertThat(journal.admit(DiagLevel.WARN, state, nowMs = 0)).contains("outcome=below")
        assertThat(journal.admit(DiagLevel.WARN, state, nowMs = 9_000)).isNull()
        val close = journal.admit(
            DiagLevel.WARN,
            state.copy(kind = DiagKind.EDGE, outcome = "recover"),
            nowMs = 9_500,
        )!!
        assertThat(close).contains("outcome=recover")
        assertThat(close).contains("skip=1")
        val next = journal.admit(DiagLevel.WARN, state, nowMs = 20_000)!!
        assertThat(next).doesNotContain("skip=")
    }

    @Test fun edgeIsNotSuppressedAndForeignSessionDoesNotTakeSkip() {
        val state = DiagFact(DiagEvent.NET_WATCH, DiagKind.STATE, state = "same", run = "10", session = "a")
        journal.admit(DiagLevel.INFO, state, nowMs = 0)
        assertThat(journal.admit(DiagLevel.INFO, state.copy(kind = DiagKind.EDGE), nowMs = 100)).isNotNull()
        journal.admit(DiagLevel.INFO, state, nowMs = 200)
        val other = journal.admit(DiagLevel.INFO, state.copy(session = "b", kind = DiagKind.EDGE), nowMs = 400)!!
        assertThat(other).doesNotContain("skip=")
        assertThat(other).contains("session=b")
        val closed = journal.admit(DiagLevel.INFO, state.copy(kind = DiagKind.EDGE, state = "changed"), nowMs = 500)!!
        assertThat(closed).contains("skip=1")
        assertThat(closed).contains("session=a")
    }

    @Test fun repeatedPidDoesNotJoinANewSessionAndMissingRunStaysApart() {
        val first = DiagFact(DiagEvent.CORE_RECONNECT, DiagKind.EDGE, run = "44", session = "1")
        val reused = first.copy(session = "2")
        val noRun = first.copy(run = null, session = "1")
        assertThat(journal.admit(DiagLevel.WARN, first, 0)).contains("run=44")
        assertThat(journal.admit(DiagLevel.WARN, reused, 1)).contains("session=2")
        val bare = journal.admit(DiagLevel.WARN, noRun, 2)!!
        assertThat(bare).doesNotContain("run=")
        assertThat(bare).contains("session=1")
    }

    @Test fun clocksAreWrittenOnlyWhenTheCallerHasThem() {
        val line = journal.admit(
            DiagLevel.DEBUG,
            DiagFact(DiagEvent.NET_REASON, DiagKind.EDGE, srcMs = 1_000, rcvMs = 81_000),
            nowMs = 81_000,
        )!!
        assertThat(line).contains("src=1000")
        assertThat(line).contains("rcv=81000")
        assertThat(line).doesNotContain("mono=")
        assertThat(line).doesNotContain("delta")
        assertThat(line).doesNotContain("cause")
        val bare = journal.admit(DiagLevel.DEBUG, DiagFact(DiagEvent.NET_REASON, DiagKind.EDGE, state = "up"), 5)!!
        assertThat(bare).doesNotContain("src=")
        assertThat(bare).doesNotContain("rcv=")
        assertThat(bare).doesNotContain("mono=")
    }

    @Test fun knownCoreLinesBecomeFactsAndABadLineDropsItsPayload() {
        val network = journal.incoming("LERNET_NETWORK reason=expert_underlay_unavailable retained_tun=true", 40)!!
        assertThat(network).contains("event=net_reason")
        assertThat(network).contains("state=expert_underlay_unavailable")
        assertThat(network).contains("rcv=40")
        val empty = journal.incoming("LERNET_NETWORK reason= retained_tun=true", 41)!!
        assertThat(empty).contains("event=net_reason")
        assertThat(empty).doesNotContain("state=")
        val health = journal.incoming("LERNET_DIAG ev=health_gate kind=edge outcome=recover code=tunnel_health_failed", 42)!!
        assertThat(health).contains("event=health_gate")
        assertThat(health).contains("outcome=recover")
        val rejected = journal.incoming("LERNET_DIAG ev=health_gate kind=edge payload=private-body", 43)!!
        assertThat(rejected).contains("event=diag_rejected")
        assertThat(rejected).doesNotContain("private-body")
        assertThat(rejected).doesNotContain("payload")
    }

    @Test fun fileFailureDoesNotCountAsProcessDeath() {
        assertThat(DiagnosticJournal.writerFailureKillsProcess(IOException("disk"))).isFalse()
        assertThat(DiagnosticJournal.writerFailureKillsProcess(IllegalStateException("stdout"))).isTrue()
        journal.noteDrop()
        val line = journal.admit(DiagLevel.WARN, DiagFact(DiagEvent.WRITER_FAIL, DiagKind.EDGE, code = "io"), 9)!!
        assertThat(line).contains("dropped=1")
        assertThat(line).contains("event=writer_fail")
    }

    @Test fun separateJournalsDoNotShareARepeat() {
        val fact = DiagFact(DiagEvent.UNDERLAY_WAIT, DiagKind.STATE, state = "absent")
        journal.admit(DiagLevel.WARN, fact, 0)
        assertThat(DiagnosticJournal().admit(DiagLevel.WARN, fact, 1)).isNotNull()
    }
}
