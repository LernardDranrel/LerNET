package app.lernet.engine.text

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalWordsTest {
    @Test fun kernelTranslatesKnownWords() {
        assertEquals("правило (rule)", JournalWords.kernel("rule"))
        assertEquals("через VPN (proxy)", JournalWords.kernel("proxy"))
        assertEquals("итог (final)", JournalWords.kernel("final"))
        assertEquals("приложение (uid 42)", JournalWords.uidLabeled(42))
        assertEquals("custom-tag", JournalWords.kernel("custom-tag"))
    }
}
