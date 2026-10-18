package app.lernet.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

/** The config column on Windows: a real mouse drag (press, move, release) moves a config into another folder. */
@OptIn(ExperimentalComposeUiApi::class)
class ConfigListDragTest {
    private fun sample(): StoredState {
        fun profile(id: String, name: String, group: String?) = StoredProfile(id, name, "VLESS",
            listOf(StoredOutbound("o-$id", "proxy", "vless",
                """{"type":"vless","tag":"proxy","server":"$id.example.net","server_port":443,"uuid":"00000000-0000-4000-8000-000000000000"}""")),
            "o-$id", groupId = group)
        return StoredState(
            groups = listOf(StoredGroup("work", "Работа"), StoredGroup("home", "Дом")),
            profiles = listOf(profile("nl", "Нидерланды", "work"), profile("de", "Германия", "work"), profile("fi", "Финляндия", "home")),
            selectedProfileId = "nl",
        )
    }

    private fun bounds(scene: ImageComposeScene, text: String): Rect {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            .first { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
            .boundsInRoot
    }

    private fun scene(controller: DesktopController, compact: Boolean) = ImageComposeScene(320, 700, Density(1f), content = {
        MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
            Surface(color = desktopColors.background) {
                val ui = controller.state.value
                ConfigList(ui.saved, TunnelSnapshot(), false, emptyMap(), controller, compact = compact,
                    onImport = {}, onGroupRoutes = {}, onExportGroup = {}, onSelect = {}) {}
            }
        }
    })

    private fun drag(scene: ImageComposeScene, from: Offset, to: Offset) {
        var time = 0L
        fun frame() { time += 16; scene.render(time * 1_000_000L).close() }
        frame()
        scene.sendPointerEvent(PointerEventType.Move, from, timeMillis = time)
        scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true))
        frame()
        val steps = 24
        for (i in 1..steps) {
            val point = from + (to - from) * (i / steps.toFloat())
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true))
            frame()
        }
        scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = time)
        frame(); frame()
    }

    private fun dropIntoHome(compact: Boolean) {
        val dir = Files.createTempDirectory("lernet-drag")
        DesktopStore(dir).save(sample())
        val controller = DesktopController(DesktopStore(dir))
        val scene = scene(controller, compact)
        try {
            scene.render(0).close()
            val source = bounds(scene, "Германия").center
            val target = bounds(scene, "Дом").center
            drag(scene, source, target)
            val moved = controller.state.value.saved.profiles.first { it.id == "de" }
            assertThat(moved.groupId).isEqualTo("home")
        } finally { scene.close(); controller.close() }
    }

    @Test fun `mouse drag moves a config into another folder`() = dropIntoHome(compact = false)

    @Test fun `drag works in the narrow column too`() = dropIntoHome(compact = true)

    @Test
    fun `a click without movement does not move anything`() {
        val dir = Files.createTempDirectory("lernet-drag-click")
        DesktopStore(dir).save(sample())
        val controller = DesktopController(DesktopStore(dir))
        val scene = scene(controller, false)
        try {
            scene.render(0).close()
            val point = bounds(scene, "Германия").center
            drag(scene, point, point)
            assertThat(controller.state.value.saved.profiles.first { it.id == "de" }.groupId).isEqualTo("work")
        } finally { scene.close(); controller.close() }
    }
}
