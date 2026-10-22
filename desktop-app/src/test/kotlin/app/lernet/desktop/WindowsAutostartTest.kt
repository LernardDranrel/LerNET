package app.lernet.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsAutostartTest {
    @Test
    fun taskStartsLerNetAtSignInWithTheAutostartArgument() {
        val xml = WindowsAutostart.taskXml(Path.of("C:\\Program Files\\LerNET\\LerNET.exe"), "PC\\Лернард & Co", elevated = true)
        assertTrue(xml.contains("<LogonTrigger>"))
        assertTrue(xml.contains("<Arguments>--autostart</Arguments>"))
        assertTrue(xml.contains("<Command>C:\\Program Files\\LerNET\\LerNET.exe</Command>"))
        assertTrue(xml.contains("<WorkingDirectory>C:\\Program Files\\LerNET</WorkingDirectory>"))
        assertTrue(xml.contains("<RunLevel>HighestAvailable</RunLevel>"))
        assertTrue(xml.contains("<UserId>PC\\Лернард &amp; Co</UserId>"))
        assertTrue(xml.contains("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>"))
        assertTrue(WindowsAutostart.taskXml(Path.of("C:\\L\\LerNET.exe"), "u", elevated = false).contains("LeastPrivilege"))
    }

    @Test
    fun autostartIsOnByDefaultAlsoForOldSettingsFiles() {
        assertTrue(StoredState().autostart)
        val dir = Files.createTempDirectory("lernet-autostart-old")
        Files.writeString(dir.resolve("state.json"), """{"tunMtu":1500}""")
        assertTrue(DesktopStore(dir).load().autostart)
    }

    @Test
    fun switchingTheSettingSyncsWindowsAndSaysWhenItFails() {
        val dir = Files.createTempDirectory("lernet-autostart")
        DesktopController(DesktopStore(dir)).use { controller ->
            val calls = java.util.concurrent.LinkedBlockingQueue<Boolean>()
            controller.setAutostartForTest(false) { calls.put(it); WindowsAutostart.Outcome.Failed("отказ") }
            assertEquals(false, calls.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(controller.state.value.saved.autostart)
            val deadline = System.currentTimeMillis() + 5_000
            while (controller.state.value.message != "отказ" && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals("отказ", controller.state.value.message)
        }
    }

    @Test
    fun sessionMarksTellACrashFromACleanExitAndRememberTheVpn() {
        val dir = Files.createTempDirectory("lernet-marks")
        DesktopSessionMarks(dir).vpnWanted()
        val afterCrash = DesktopSessionMarks(dir)
        assertTrue(afterCrash.previousSessionCrashed)
        assertTrue(afterCrash.resumeWanted)
        afterCrash.closedCleanly()
        val afterExit = DesktopSessionMarks(dir)
        assertFalse(afterExit.previousSessionCrashed)
        assertTrue(afterExit.resumeWanted)
        afterExit.vpnStopped()
        assertFalse(DesktopSessionMarks(dir).resumeWanted)
    }

    @Test
    fun crashOfThePreviousSessionIsSaidAtStart() {
        val dir = Files.createTempDirectory("lernet-crash")
        DesktopSessionMarks(dir).vpnWanted()
        DesktopController(DesktopStore(dir)).use { controller ->
            assertEquals(DesktopSessionMarks.CRASH_NOTICE, controller.state.value.message)
        }
        // close() was clean: the next start says nothing.
        DesktopController(DesktopStore(dir)).use { controller ->
            assertEquals("", controller.state.value.message)
        }
    }

    @Test
    fun crashGuardHintSaysWhatHappensWhenLerNetItselfCrashes() {
        assertTrue(SecurityTexts.CRASH_GUARD_HINT.contains("Если аварийно закроется сам LerNET, Windows снимет эту защиту"))
    }

    @Test
    fun switchingToExpertMeansTheBasicVpnIsNotBroughtBackAtSignIn() {
        val dir = java.nio.file.Files.createTempDirectory("lernet-expert-resume")
        val controller = DesktopController(DesktopStore(dir))
        controller.sessionMarks.vpnWanted()
        controller.reserveExpertMode()
        assertFalse(DesktopSessionMarks(dir).resumeWanted)
        controller.releaseExpertMode()
        controller.close()
    }
}
