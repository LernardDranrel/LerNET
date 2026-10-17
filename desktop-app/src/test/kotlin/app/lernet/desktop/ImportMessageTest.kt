package app.lernet.desktop

import org.junit.Test
import org.junit.Assert.assertEquals

class ImportMessageTest {
    @Test
    fun namesUnusedPartsOnScreen() {
        assertEquals("Добавлено профилей: 1", importMessage(1, emptyList()))
        assertEquals(
            "Добавлено профилей: 1. Эти части конфига не используются: входы (inbounds: 1) — LerNET создаёт свои; раздел «log»",
            importMessage(1, listOf("входы (inbounds: 1) — LerNET создаёт свои", "раздел «log»")),
        )
    }
}
