package app.lernet.desktop.observation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ObservationIconPolicyTest {
    @Test fun uncAndDevicePathsNeverReachTheFilesystem() {
        for (path in listOf("\\\\server\\share\\app.exe", "\\Device\\Mup\\server\\app.exe", "app.exe")) {
            assertThat(ObservationIconPolicy.allows(path, { error("Must not query drive") }, { error("Must not query files") })).isFalse()
        }
    }

    @Test fun mappedDriveOrJunctionNeverLoadsRemoteChild() {
        assertThat(ObservationIconPolicy.allows("Z:\\app.exe", { false }, { error("Remote drive") })).isFalse()
        val checked = mutableListOf<String>()
        assertThat(ObservationIconPolicy.allows("C:\\junction\\remote\\app.exe", { true }, { path -> checked += path; !path.endsWith("junction") })).isFalse()
        assertThat(checked).hasSize(1)
    }
}
