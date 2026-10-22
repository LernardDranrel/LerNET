package app.lernet.desktop

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalExportTest {
    @Test
    fun savedJournalHasBothSegmentsOldestFirstAndNoSecrets() {
        val dir = Files.createTempDirectory("lernet-journal")
        Files.writeString(dir.resolve("session.log.1"), "старый сегмент vless://uuid@host:443\n")
        Files.writeString(dir.resolve("session.log"), "новый сегмент\n")
        val text = WindowsBoxProcess(dir).journalText("LerNET test")
        assertTrue(text.startsWith("LerNET test\n\n"))
        assertTrue(text.indexOf("старый сегмент") < text.indexOf("новый сегмент"))
        assertFalse(text.contains("uuid@host"))
    }

    @Test
    fun emptyJournalSaysSo() {
        val dir = Files.createTempDirectory("lernet-journal-empty")
        assertTrue(WindowsBoxProcess(dir).journalText("h").contains("Журнал пуст"))
    }

    @Test
    fun saveJournalWritesTheChosenFileAndCopyHasAHeader() {
        val dir = Files.createTempDirectory("lernet-journal-save")
        DesktopController(DesktopStore(dir)).use { controller ->
            controller.tunnel.logDiagnostic("проверка trojan://secret@host:443")
            val target = dir.resolve("export.txt")
            controller.saveJournal(target)
            val deadline = System.currentTimeMillis() + 5_000
            while (!Files.exists(target) && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val saved = Files.readString(target)
            assertTrue(saved.startsWith("LerNET "))
            assertTrue(saved.contains("проверка"))
            assertFalse(saved.contains("secret@host"))
            val copied = controller.journalForClipboard()
            assertTrue(copied.contains("проверка"))
            assertFalse(copied.contains("secret@host"))
        }
    }
}
