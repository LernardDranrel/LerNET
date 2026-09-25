package app.lernet.engine.log

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DurableFileTest {
    @Test
    fun writeReplacesTargetAndSurvivesReread() {
        val dir = TemporaryFolder()
        dir.create()
        try {
            val target = File(dir.root, "session.log")
            DurableFile.write(target, "first")
            assertThat(target.readText()).isEqualTo("first")
            DurableFile.write(target, "second-line")
            assertThat(target.readText()).isEqualTo("second-line")
            assertThat(File(dir.root, "session.log.tmp").exists()).isFalse()
        } finally {
            dir.delete()
        }
    }
}
