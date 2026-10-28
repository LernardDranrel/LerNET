package app.lernet.engine.text

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusWordsChromeTest {
    @Test fun chromePrefixesLerNet() {
        assertEquals("LerNET · Подключено", StatusWords.chrome(StatusWords.CONNECTED))
        assertEquals("LerNET · Отключено", StatusWords.chrome(StatusWords.OFF))
        assertEquals("LerNET · Эксперт", StatusWords.chrome(StatusWords.EXPERT))
    }
}
