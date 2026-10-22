package app.lernet.engine.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertWordsProtectionTest {
    @Test fun sameCriteriaOnBothPlatforms() {
        assertTrue(ExpertWords.protectionOk(0, true, true))
        assertFalse(ExpertWords.protectionOk(1, true, true))
        assertFalse(ExpertWords.protectionOk(0, false, true))
        assertFalse(ExpertWords.protectionOk(0, true, false))
        assertEquals(ExpertWords.PROTECTION_ON, ExpertWords.protectionValue(true))
        assertEquals(ExpertWords.PROTECTION_CHECK, ExpertWords.protectionValue(false))
    }
}
