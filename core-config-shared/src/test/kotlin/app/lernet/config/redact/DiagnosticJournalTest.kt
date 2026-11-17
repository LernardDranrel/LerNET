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
        assertThat(DiagnosticJournal.writerFailureKillsProcess(IllegalStateException("redactor"))).isFalse()
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

    @Test fun ordinaryDnsAndRawLinesHideAddressAtWarnAndKeepItAtInfo() {
        val dns = "dns query ok domain=dns.example type=1 rcode=0 answers=1 serverType=udp server=1.1.1.1"
        val warn = journal.text(DiagLevel.WARN, dns, keepAddress = false, nowMs = 1)
        val info = journal.text(DiagLevel.INFO, dns, keepAddress = false, nowMs = 2)
        assertThat(warn).doesNotContain("dns.example")
        assertThat(warn).doesNotContain("1.1.1.1")
        assertThat(warn).contains("domain=*")
        assertThat(info).contains("domain=dns.example")
        assertThat(info).contains("1.1.1.1")
        val raw = "dial tcp dns.example:443 mac=aa:bb:cc:dd:ee:ff ssid=HomeNet " +
            """"password":"s3cret" vless://abc@h:443"""
        val hidden = journal.text(DiagLevel.WARN, raw, keepAddress = false, nowMs = 3)
        assertThat(hidden).doesNotContain("dns.example")
        assertThat(hidden).doesNotContain("aa:bb:cc:dd:ee:ff")
        assertThat(hidden).doesNotContain("HomeNet")
        assertThat(hidden).doesNotContain("s3cret")
        assertThat(hidden).doesNotContain("vless://abc")
        val shown = journal.text(DiagLevel.INFO, raw, keepAddress = false, nowMs = 4)
        assertThat(shown).contains("dns.example")
        assertThat(shown).doesNotContain("aa:bb:cc:dd:ee:ff")
        assertThat(shown).doesNotContain("HomeNet")
        assertThat(shown).doesNotContain("s3cret")
        val exported = SecretRedactor.redact(info)
        assertThat(exported).contains("dns.example")
        assertThat(exported).doesNotContain("s3cret")
    }

    @Test fun explicitHopSurvivesWarnAndAPollCounterDoesNotDefeatSuppression() {
        val hop = journal.text(DiagLevel.WARN, "event=hop_check dest=203.0.113.9 dns.example", true, 1)
        assertThat(hop).contains("203.0.113.9")
        assertThat(hop).contains("dns.example")
        val first = DiagFact(DiagEvent.UNDERLAY_WAIT, DiagKind.STATE, state = "absent", attempt = 1)
        assertThat(journal.admit(DiagLevel.WARN, first, 0)).isNotNull()
        assertThat(journal.admit(DiagLevel.WARN, first.copy(attempt = 2), 1_000)).isNull()
        val found = journal.admit(DiagLevel.WARN, first.copy(kind = DiagKind.EDGE, outcome = "found", attempt = 3), 2_000)!!
        assertThat(found).contains("skip=1")
    }

    @Test fun reusedPidAndResetGenerationDoNotJoin() {
        val older = DiagFact(DiagEvent.CORE_RECONNECT, DiagKind.STATE, run = "9:1000", session = "1", state = "again")
        journal.admit(DiagLevel.WARN, older, 0)
        assertThat(journal.admit(DiagLevel.WARN, older.copy(run = "9:2000"), 100)).isNotNull()
        val fresh = DiagnosticJournal()
        assertThat(fresh.admit(DiagLevel.WARN, older, 100)).isNotNull()
        val below = DiagFact(DiagEvent.HEALTH_GATE, DiagKind.STATE, outcome = "below", state = "f1", session = "1")
        journal.admit(DiagLevel.DEBUG, below, 0)
        assertThat(journal.admit(DiagLevel.DEBUG, below, 500)).isNull()
        val reset = journal.admit(DiagLevel.WARN, below.copy(kind = DiagKind.EDGE, outcome = "recover", state = "f0"), 800)!!
        assertThat(reset).contains("skip=1")
        val again = journal.admit(DiagLevel.DEBUG, below, 20_000)!!
        assertThat(again).doesNotContain("skip=")
    }

    @Test fun cancelFlushesTheStartGroupAndTheMapStaysBounded() {
        val held = DiagFact(DiagEvent.START_RESULT, DiagKind.STATE, outcome = "wait", session = "1")
        journal.admit(DiagLevel.WARN, held, 0)
        journal.admit(DiagLevel.WARN, held, 100)
        val cancel = journal.admit(DiagLevel.WARN, DiagFact(DiagEvent.START_CANCEL, DiagKind.EDGE, session = "1"), 200)!!
        assertThat(cancel).contains("skip=1")
        repeat(300) { index ->
            journal.admit(DiagLevel.WARN, DiagFact(DiagEvent.NET_WATCH, DiagKind.EDGE, state = "n$index"), index.toLong())
        }
        assertThat(journal.admit(DiagLevel.WARN, DiagFact(DiagEvent.NET_REASON, DiagKind.EDGE, state = "up"), 400)).contains("event=net_reason")
    }
}
