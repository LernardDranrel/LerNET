package app.lernet.desktop.expert

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
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
                        DesktopExpert(
                            ExpertUiState(NetworkPolicy(), administrator = true, reducedMotion = true),
                            intents::add,
                            topChrome = { DesktopModeTabs(true, modes::add, reducedMotion = true) },
                        )
                    }
                }
            }
            try {
                screenshot(scene, "workspace-$width-overview")
                // Sidebar flush to window top (same nesting as VPN): brand sits above the mode-tab strip height.
                val brand = nodes(scene).first { "для Windows" in texts(it) }
                assertThat(brand.boundsInRoot.top).isAtMost(40f)
                val vpnTab = nodes(scene).first { "VPN" in texts(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertThat(vpnTab.boundsInRoot.left).isAtLeast(196f)
                // The pult content («Своя схема») sits right of the section column (N1: sections live in the left column).
                val heading = nodes(scene).first { "Своя схема" in texts(it) }
                assertThat(heading.boundsInRoot.left).isAtLeast(218f)
                assertThat(heading.boundsInRoot.right).isAtMost(width - 22f)
                // The v3 sections (m3): the same five as the phone, then «Защита и DNS» under «Настройки».
                listOf("Схема", "Конфиги", "Проверка пути", "События", "Защита и DNS").forEach { title ->
                    val navigation = nodes(scene).first {
                        title in texts(it) && it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                    assertThat(navigation.boundsInRoot.left).isAtLeast(14f)
                    assertThat(navigation.boundsInRoot.right).isAtMost(182f)
                    navigation.config[SemanticsActions.OnClick].action!!.invoke()
                    screenshot(scene, "workspace-$width-${title.hashCode()}")
                }
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
            // v2 wording of the same fact: off, and the Windows guard blocks the ordinary way out.
            assertThat(nodes(scene).any { texts(it).any { t -> t.startsWith("Выключено · защита Windows блокирует обычный выход") } }).isTrue()
            assertThat(nodes(scene).any { "Сеть работает как обычно" in texts(it) }).isFalse()
        } finally {
            scene.close()
        }
    }


    @Test
    fun workspaceLoadingHidesSchemaUntilReady() {
        val scene = ImageComposeScene(1040, 720, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                DesktopExpert(
                    ExpertUiState(NetworkPolicy(), administrator = true, reducedMotion = true, workspaceReady = false),
                    {},
                )
            }
        }
        try {
            repeat(2) { scene.render(0).close() }
            val all = nodes(scene).flatMap { texts(it) }
            assertThat(all).contains("Читаем настройки и конфиги…")
            assertThat(all.any { "Добавить правило" in it }).isFalse()
        } finally {
            scene.close()
        }
    }

    @Test
    fun workspaceFailShowsOneBannerNotDualGap() {
        // Live beta6 bug: refreshInventory catch set both error and workspaceError → two red banners
        // and a huge empty Box(weight(1f), Center) between them.
        val scene = ImageComposeScene(1040, 720, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                DesktopExpert(
                    ExpertUiState(
                        NetworkPolicy(), administrator = true, reducedMotion = true,
                        workspaceReady = false,
                        workspaceError = "Не удалось прочитать рабочую область.",
                        error = "Не удалось прочитать рабочую область.",
                    ),
                    {},
                )
            }
        }
        try {
            screenshot(scene, "workspace-fail-one-banner")
            val all = nodes(scene).flatMap { texts(it) }
            val joined = all.joinToString("\n")
            assertThat(joined).contains("Повторить")
            assertThat(joined).contains("Не удалось прочитать рабочую область")
            val operationBanners = all.count { it == "Операция не завершена" }
            val workspaceBanners = all.count { it == "Рабочая область не загрузилась" }
            assertThat(operationBanners + workspaceBanners).isEqualTo(1)
            assertThat(joined).doesNotContain("ещё загружается")
        } finally {
            scene.close()
        }
    }

    @Test
    fun workspaceErrorOffersRetryInsteadOfStillLoading() {
        val scene = ImageComposeScene(1040, 720, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                DesktopExpert(
                    ExpertUiState(
                        NetworkPolicy(), administrator = true, reducedMotion = true,
                        workspaceReady = false, workspaceError = "Файл схемы повреждён",
                    ),
                    {},
                )
            }
        }
        try {
            repeat(2) { scene.render(0).close() }
            val all = nodes(scene).flatMap { texts(it) }.joinToString("\n")
            assertThat(all).contains("Рабочая область не загрузилась")
            assertThat(all).contains("Файл схемы повреждён")
            assertThat(all).contains("Повторить")
            assertThat(all).doesNotContain("ещё загружается")
        } finally {
            scene.close()
        }
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        repeat(3) { scene.render(0).close() }
        scene.render(0).use { image ->
            val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
            listOf(
                File("build/expert-ui-review"),
                File("/workspace/projects/LerNET-ui3/out/windows-expert-ui"),
            ).forEach { dir ->
                dir.mkdirs()
                dir.resolve("$name.png").writeBytes(bytes)
            }
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
