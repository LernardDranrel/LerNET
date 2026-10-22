package app.lernet.desktop

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DesktopStoreLegacyTest {
    @Test
    fun stateWithOldCustomCorePathStillLoadsAndForgetsIt() {
        val dir = Files.createTempDirectory("lernet-legacy-core")
        Files.writeString(dir.resolve("state.json"), """{"corePath":"C:\\custom\\sing-box.exe","tunMtu":1400}""")
        val store = DesktopStore(dir)
        val loaded = store.load()
        assertEquals(1400, loaded.tunMtu)
        store.save(loaded)
        assertFalse(Files.readString(dir.resolve("state.json")).contains("corePath"))
    }
}
