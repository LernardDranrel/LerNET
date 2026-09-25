package app.lernet.engine.log

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableRingFileTest {
    @Test
    fun readsOnlyCompleteRecentLines() {
        val dir = TemporaryFolder()
        dir.create()
        try {
            val file = File(dir.root, "session-ring.log")
            file.writeText("alpha\nbravo\ncharlie\n")
            val ring = DurableRingFile(file)

            assertThat(ring.readText(maxReadBytes = 16)).isEqualTo("bravo\ncharlie\n")
        } finally {
            dir.delete()
        }
    }

    @Test
    fun rotatesLargeExistingJournalWithoutLosingNewestEntries() {
        val dir = TemporaryFolder()
        dir.create()
        try {
            val file = File(dir.root, "session-ring.log")
            file.bufferedWriter().use { writer ->
                repeat(80_000) { index -> writer.appendLine("entry-$index ${"x".repeat(48)}") }
            }
            val ring = DurableRingFile(file, maxBytes = 2 * 1024 * 1024)

            ring.applyCap(2 * 1024 * 1024)

            assertThat(file.length()).isAtMost(2L * 1024 * 1024)
            assertThat(ring.readText()).contains("entry-79999")
            assertThat(file.bufferedReader().use { it.readLine() }).startsWith("entry-")
        } finally {
            dir.delete()
        }
    }

    @Test
    fun appendsAndRotatesUnderCap() {
        val dir = TemporaryFolder()
        dir.create()
        try {
            val file = File(dir.root, "session-ring.log")
            val ring = DurableRingFile(file, maxBytes = 80)
            repeat(20) { index ->
                ring.append("crumb-$index ${"x".repeat(8)}")
            }
            val text = ring.readText()
            assertThat(text).contains("crumb-19")
            assertThat(file.length()).isAtMost(80L + 40L)
            assertThat(text.lines().any { it.startsWith("crumb-0") }).isFalse()
        } finally {
            dir.delete()
        }
    }
}
