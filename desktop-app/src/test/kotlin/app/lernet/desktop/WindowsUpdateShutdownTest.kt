package app.lernet.desktop

import org.junit.Assert.*
import org.junit.Test

class WindowsUpdateShutdownTest {
    @Test fun shutdownSignalIsScopedToInstallationAndCaseInsensitiveOnWindows() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val name = WindowsUpdateShutdown.eventName("C:\\Fixtures\\LerNET\\LerNET.exe")
        assertEquals(name, WindowsUpdateShutdown.eventName("c:\\fixtures\\lernet\\lernet.exe"))
        assertNotEquals(name, WindowsUpdateShutdown.eventName("C:\\Portable\\LerNET.exe"))
        assertTrue(name.matches(Regex("Local\\\\LerNET\\.Update\\.[a-f0-9]{64}")))
    }
}
