package app.lernet.desktop.expert

import androidx.compose.foundation.layout.Box
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
import java.nio.file.Files
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class DesktopExpertV2Test {
    @Test
    fun `the side column lists the five expert sections and settings stay aside`() {
        // UI v3 (mockup 3): the sections moved to the left column; the simulator is «Проверка пути» (N7).
        assertThat(ExpertNavPages.map { it.title }).containsExactly("Пульт", "Схема", "Конфиги", "Проверка пути", "События").inOrder()
        assertThat(ExpertNavPages).doesNotContain(ExpertPage.PROTECTION)
    }

    @Test
    fun `uptime is shown only for a confirmed running tunnel`() {
        val running = ExpertUiState(NetworkPolicy(), phase = ExpertPhase.RUNNING, runningSinceEpochMs = 0, tunnelHealth = app.lernet.engine.policy.ExpertTunnelHealth.HEALTHY)
        assertThat(expertUptimeLabel(running, ((14 * 24 + 6) * 60 + 12) * 60_000L)).isEqualTo("14 дн 06 ч")
        assertThat(expertUptimeLabel(running.copy(runningSinceEpochMs = null), 1_000)).isNull()
        assertThat(expertUptimeLabel(running.copy(phase = ExpertPhase.STOPPED), 1_000)).isNull()
        // Still checking the link: no counter next to «проверяем связь».
        assertThat(expertUptimeLabel(running.copy(tunnelHealth = app.lernet.engine.policy.ExpertTunnelHealth.PENDING), 120_000)).isNull()
        assertThat(expertUptimeLabel(running.copy(phase = ExpertPhase.APPLYING), 120_000)).isEqualTo("2 мин")
    }

    @Test
    fun `splash marker is written once`() {
        val dir = Files.createTempDirectory("lernet-splash")
        val memory = DesktopExpertSplashMemory(dir)
        assertThat(memory.seen()).isFalse()
        memory.markSeen()
        assertThat(memory.seen()).isTrue()
        assertThat(DesktopExpertSplashMemory(dir).seen()).isTrue()
    }

    @Test
    fun `bottom menu switches pages and the frame does not start networking`() {
        val intents = mutableListOf<ExpertIntent>()
        val state = ExpertUiState(
            NetworkPolicy(), administrator = true, reducedMotion = true,
            phase = ExpertPhase.RUNNING, desiredEnabled = true,
            tunnelHealth = app.lernet.engine.policy.ExpertTunnelHealth.HEALTHY, appliedRevision = 0,
            runningSinceEpochMs = System.currentTimeMillis() - ((14 * 24 + 6) * 60 + 12) * 60_000L,
        )
        val scene = ImageComposeScene(1440, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                androidx.compose.material3.Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) {
                    Column(Modifier.fillMaxSize()) {
                        DesktopModeTabs(true, {}, reducedMotion = true)
                        DesktopExpert(state, intents::add, frameAnimateIn = false)
                    }
                }
            }
        }
        try {
            screenshot(scene, "windows-expert-v2-pult")
            assertThat(nodes(scene).any { "14 дн 06 ч" in texts(it) }).isTrue()
            listOf("Схема" to "schema", "Конфиги" to "configs", "Правила" to "rules", "События" to "events").forEach { (title, file) ->
                val tab = nodes(scene).last { title in texts(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertThat(tab.boundsInRoot.bottom).isAtLeast(800f)
                tab.config[SemanticsActions.OnClick].action!!.invoke()
                screenshot(scene, "windows-expert-v2-$file")
            }
            assertThat(intents).isEmpty()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `splash renders with frame`() {
        val scene = ImageComposeScene(1440, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                Box(Modifier.fillMaxSize()) { DesktopExpertSplash() }
            }
        }
        try {
            screenshot(scene, "windows-expert-v2-splash")
            assertThat(nodes(scene).any { "Эксперт" in texts(it) }).isTrue()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `configs page is one table with state, latency and the next action`() {
        val exits = listOf(
            ExpertExit("a", "Amsterdam", "Европа · постоянный", app.lernet.engine.policy.ExitPhase.READY, latencyMs = 42, activeFlows = 14, canSleep = true),
            ExpertExit("o", "Офис (WireGuard)", "Работа · холодный", app.lernet.engine.policy.ExitPhase.READY, latencyMs = 18, activeFlows = 3,
                coldStart = true, reason = "Проснулся для mail.corp.example · уснёт после 30 мин тишины", canSleep = true),
            ExpertExit("t", "Tokyo", "Работа · холодный", app.lernet.engine.policy.ExitPhase.SLEEPING, coldStart = true,
                reason = "Уснул 21:12 · 30 мин без трафика", canWake = true),
            ExpertExit("h", "Helsinki", "Европа · постоянный", app.lernet.engine.policy.ExitPhase.FAILED,
                reason = "Не отвечает с 20:41 · TLS: время ожидания истекло · проверяем раз в 5 мин"),
        )
        val state = ExpertUiState(
            NetworkPolicy(), administrator = true, reducedMotion = true, phase = ExpertPhase.RUNNING, desiredEnabled = true,
            tunnelHealth = app.lernet.engine.policy.ExpertTunnelHealth.HEALTHY, appliedRevision = 0, exits = exits,
        )
        val scene = ImageComposeScene(1440, 900, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                androidx.compose.material3.Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) {
                    DesktopExpert(state, {}, frameAnimateIn = false)
                }
            }
        }
        try {
            val tab = nodes(scene).last { "Конфиги" in texts(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
            tab.config[SemanticsActions.OnClick].action!!.invoke()
            screenshot(scene, "windows-expert-v2-configs-table")
            listOf("КОНФИГ", "СОСТОЯНИЕ", "ЗАДЕРЖКА", "Amsterdam", "42 мс", "Разбудить", "Усыпить").forEach { word ->
                assertThat(nodes(scene).any { word in texts(it) }).isTrue()
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `failover report says which configs were tried and that traffic does not leak`() {
        val now = System.currentTimeMillis()
        val report = app.lernet.desktop.FailoverReport(
            "Европа",
            listOf(
                app.lernet.desktop.FailoverAttempt("Amsterdam", "сервер не отвечает", now - 120_000),
                app.lernet.desktop.FailoverAttempt("Frankfurt", "сервер отвечает, но трафик через него не идёт", now - 60_000),
                app.lernet.desktop.FailoverAttempt("Helsinki", "TLS: время ожидания истекло", now),
            ),
            "1 мин 10 с",
        )
        val scene = ImageComposeScene(760, 260, Density(1f)) {
            MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
                androidx.compose.material3.Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) {
                    Box(Modifier.fillMaxSize()) { app.lernet.desktop.FailoverReportCard(report) }
                }
            }
        }
        try {
            screenshot(scene, "windows-basic-all-configs-down")
            assertThat(nodes(scene).any { texts(it).any { t -> "трафик не уходит в обход" in t } }).isTrue()
            assertThat(nodes(scene).any { "Frankfurt" in texts(it) }).isTrue()
        } finally {
            scene.close()
        }
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        println("DBGTEXTS[$name]: " + nodes(scene).flatMap { texts(it) })  // DBG
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
