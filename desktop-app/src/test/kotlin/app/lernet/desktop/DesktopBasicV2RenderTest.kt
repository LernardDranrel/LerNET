package app.lernet.desktop

import app.lernet.engine.text.SettingsWords
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/** Mockup 03: rules, journal and settings open from the top; «Дополнительно» in settings is folded. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopBasicV2RenderTest {
    @Test
    fun `top buttons open settings and advanced knobs stay folded until asked`() {
        val controller = DesktopController(DesktopStore(Files.createTempDirectory("lernet-basic-v2")))
        val scene = ImageComposeScene(1440, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) {
                    Column(Modifier.fillMaxSize()) {
                        DesktopModeTabs(false, {}, reducedMotion = true)
                        DesktopScreen(controller, {}, {})
                    }
                }
            }
        }
        try {
            screenshot(scene, "windows-basic-v2-home")
            listOf("Правила", "Журнал запросов", "Настройки", "Анализ сети ↗").forEach { word ->
                assertThat(nodes(scene).any { texts(it).any { t -> t == word } }).isTrue()
            }
            click(scene, "Настройки")
            screenshot(scene, "windows-basic-v2-settings")
            assertThat(nodes(scene).any { SettingsWords.MTU_TITLE in texts(it) }).isFalse()
            // S1: the advanced knobs are a separate «Для опытных» page.
            click(scene, "Для опытных")
            screenshot(scene, "windows-basic-v2-settings-advanced")
            assertThat(nodes(scene).any { SettingsWords.MTU_TITLE in texts(it) }).isTrue()
        } finally {
            scene.close()
            controller.close()
        }
    }

    private fun click(scene: ImageComposeScene, title: String) {
        fun owner(node: SemanticsNode?): SemanticsNode? = when {
            node == null -> null
            node.config.getOrNull(SemanticsActions.OnClick) != null -> node
            else -> owner(node.parent)
        }
        val node = owner(nodes(scene).first { title in texts(it) })!!
        node.config[SemanticsActions.OnClick].action!!.invoke()
        scene.render(0).close()
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        var t = 0L
        repeat(4) { scene.render(t).close(); t += 500_000_000L }
        scene.render(t).use { image ->
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
