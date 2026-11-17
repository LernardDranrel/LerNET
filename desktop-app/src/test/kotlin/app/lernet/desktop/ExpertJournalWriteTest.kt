package app.lernet.desktop

import app.lernet.config.redact.DiagnosticJournal
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class ExpertJournalWriteTest {
    @Test fun diskErrorIsRememberedAndTheFiveMegabyteRotationStays() {
        val root = Files.createTempDirectory("expert-journal")
        val journal = DiagnosticJournal()
        writeExpertJournal(journal, root, "LERNET_NETWORK reason=expert_underlay_unavailable retained_tun=true", 10)
        val current = root.resolve("expert.log")
        assertThat(Files.readString(current)).contains("event=net_reason")
        Files.write(current, ByteArray(5_000_001))
        writeExpertJournal(journal, root, "LERNET_DIAG ev=process_exit kind=edge code=dead", 11)
        assertThat(Files.exists(root.resolve("expert.log.1"))).isTrue()
        assertThat(Files.readString(current)).contains("event=process_exit")
        val blocked = Files.createTempDirectory("expert-blocked").resolve("not-dir")
        Files.writeString(blocked, "x")
        writeExpertJournal(journal, blocked, "next", 12)
        val remembered = journal.incoming("after-disk", 13)!!
        assertThat(remembered).contains("dropped=1")
        assertThat(remembered).contains("after-disk")
    }
}
