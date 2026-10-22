package app.lernet.desktop

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Windows keeps the same «rest» list as Android: it survives a restart of the app. */
class DesktopResilienceTest {
    @Test fun restingConfigsSurviveARestartAndAreNotClearedOnStart() {
        val directory = Files.createTempDirectory("lernet-penalties")
        val now = System.currentTimeMillis()
        val first = DesktopController(DesktopStore(directory))
        try {
            first.penalties.fail("dead", now)
            assertTrue(first.penalties.isResting("dead", now))
        } finally {
            first.close()
        }
        val second = DesktopController(DesktopStore(directory))
        try {
            assertTrue(second.penalties.isResting("dead", System.currentTimeMillis()))
            assertFalse(second.penalties.isResting("alive", System.currentTimeMillis()))
        } finally {
            second.close()
        }
    }
}
