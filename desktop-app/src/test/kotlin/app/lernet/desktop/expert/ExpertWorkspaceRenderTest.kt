package app.lernet.desktop.expert

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.lernet.desktop.DesktopModeTabs
import app.lernet.desktop.desktopColors
import app.lernet.desktop.desktopTypography
import app.lernet.routing.policy.NetworkPolicy
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/** Production chrome rendered without any controller, native library, process or network. */
@OptIn(ExperimentalComposeUiApi::class)
class ExpertWorkspaceRenderTest {
    @Test
    fun `workspace navigation stays inside the window and never starts networking`() {
        listOf(1040 to 760, 1440 to 900).forEach { (width, height) ->
            val intents = mutableListOf<ExpertIntent>()
            val modes = mutableListOf<Boolean>()
            val scene = ImageComposeScene(width, height, Density(1f)) {
                MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                    androidx.compose.material3.Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) {
                        Column(Modifier.fillMaxSize()) {
                            DesktopModeTabs(true, modes::add, reducedMotion = true)
                            DesktopExpert(ExpertUiState(NetworkPolicy(), administrator = true, reducedMotion = true), intents::add)
                        }
                    }
                }
            }
            try {
                screenshot(scene, "workspace-$width-overview")
                val heading = nodes(scene).first { "Управление сетью" in texts(it) }
                assertThat(heading.boundsInRoot.left).isAtLeast(218f)
                assertThat(heading.boundsInRoot.right).isAtMost(width - 22f)
                listOf("Схема", "Проверка пути", "Выходы", "Соединения", "События", "Защита").forEach { title ->
                    val navigation = nodes(scene).first {
                        title in texts(it) && it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                    assertThat(navigation.boundsInRoot.left).isAtLeast(14f)
                    assertThat(navigation.boundsInRoot.right).isAtMost(182f)
                    navigation.config[SemanticsActions.OnClick].action!!.invoke()
                    screenshot(scene, "workspace-$width-${title.hashCode()}")
                }
                val vpnTab = nodes(scene).first { "VPN" in texts(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
                vpnTab.config[SemanticsActions.OnClick].action!!.invoke()
                assertThat(modes).containsExactly(false)
                assertThat(intents).isEmpty()
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `stopped intermediary does not claim ordinary internet when persistent guard is enabled`() {
        val scene = ImageComposeScene(1180, 760, Density(1f, 1.25f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                androidx.compose.material3.Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) {
                    DesktopExpert(
                        ExpertUiState(
                            NetworkPolicy(), reducedMotion = true,
                            protection = ExpertProtection(systemGuardEnforced = true)
                        ),
                        {},
                    )
                }
            }
        }
        try {
            screenshot(scene, "workspace-scaled-guard")
            assertThat(nodes(scene).any { "Управление выключено; системная защита включена" in texts(it) }).isTrue()
            assertThat(nodes(scene).any { "Сеть работает как обычно" in texts(it) }).isFalse()
        } finally {
            scene.close()
        }
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        repeat(3) { scene.render(0).close() }
        scene.render(0).use { image ->
            val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
            File("build/expert-ui-review").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
