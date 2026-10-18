package app.lernet.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.lernet.engine.policy.FailoverAttempt
import app.lernet.engine.policy.FailoverReport
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/** Every basic-mode state of the Windows window, wide and narrow, saved to build/ux-states/ for the review. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopStatesRenderTest {
    private fun sample(): StoredState {
        fun profile(id: String, name: String, group: String?) = StoredProfile(id, name, "VLESS",
            listOf(StoredOutbound("o-$id", "proxy", "vless",
                """{"type":"vless","tag":"proxy","server":"$id.example.net","server_port":443,"uuid":"00000000-0000-4000-8000-000000000000"}""")),
            "o-$id", groupId = group)
        return StoredState(
            groups = listOf(StoredGroup("work", "Работа", autoSwap = true), StoredGroup("home", "Дом")),
            profiles = listOf(profile("nl", "Нидерланды", "work"), profile("de", "Германия", "work"),
                profile("fi", "Финляндия", "home"), profile("solo", "Личный", null)),
            rules = listOf(
                StoredRule("r1", "grp_work", sortIndex = 0, title = "Рабочие сайты", domainSuffixes = listOf("corp.example")),
                StoredRule("r2", "grp_work", sortIndex = 1, title = "Банки напрямую", action = "DIRECT", domainSuffixes = listOf("bank.example")),
            ),
            selectedProfileId = "nl",
        )
    }

    private val goodProbes = mapOf(
        "nl" to ProbeResult(latencyMs = 34, tunnelLatencyMs = 128),
        "de" to ProbeResult(latencyMs = 51, tunnelLatencyMs = null, tunnelMessage = "Required value was null."),
        "fi" to ProbeResult(latencyMs = null, message = "нет ответа"),
    )

    private fun theme(content: @Composable () -> Unit) = @Composable {
        MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
            Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) { content() }
        }
    }

    private fun render(name: String, width: Int, height: Int, state: StoredState = sample(), preview: DesktopScreenPreview) {
        val dir = Files.createTempDirectory("lernet-states")
        DesktopStore(dir).save(state)
        val controller = DesktopController(DesktopStore(dir))
        val scene = ImageComposeScene(width, height, Density(1f), content = theme {
            Column(Modifier.fillMaxSize()) {
                DesktopModeTabs(false, {}, reducedMotion = true)
                DesktopScreen(controller, {}, {}, preview = preview)
            }
        })
        try {
            var t = 0L
            repeat(4) { scene.render(t).close(); t += 500_000_000L }
            scene.render(t).use { image ->
                val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                File("build/ux-states").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
            }
            val all = texts(scene)
            listOf("TCP", "Канал HTTP", "B/s", "хоп", "Required value", "Exception", "null.", "sing-box JSON", "VPN/TUN").forEach { word ->
                assertThat(all.filter { word in it }.map { "$name: $it" }).isEmpty()
            }
            if (preview.page == null) {
                // The state is written once on the home: under the button only.
                val states = listOf("Отключено", "Подключено", "Подключаемся…", "Нет связи · VPN держим", "Не удалось подключиться", "Готово к подключению")
                states.forEach { s -> assertThat(all.count { it == s }).isAtMost(1) }
                // One action per state: while holding only «Выключить VPN», never a second «Отключить» caption.
                if ("Нет связи · VPN держим" in all) {
                    assertThat(all.filter { it == "Отключить" }.map { "$name: $it" }).isEmpty()
                    assertThat(all.count { it == "Выключить VPN" }).isEqualTo(1)
                }
            }
        } finally { scene.close(); controller.close() }
    }

    private fun texts(scene: ImageComposeScene): List<String> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
    }

    private val off = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.STOPPED), { it.copy(probes = goodProbes) })
    private val connecting = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.STARTING, "Запускаем VPN"),
        { it.copy(busy = true, message = "Запускаем VPN", probes = goodProbes) })
    private val connected = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.RUNNING, "Подключено"),
        { it.copy(probes = goodProbes, healthMessage = "VPN отвечает · 128 мс", healthVerified = true, tunnelLatencyMs = 128) })
    private val failed = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.FAILED, "VPN не запустился. Подробности — в журнале."),
        { it.copy(probes = goodProbes) })
    private fun holding(): DesktopScreenPreview {
        val now = System.currentTimeMillis()
        val report = FailoverReport("Работа", listOf(
            FailoverAttempt("Нидерланды", "сервер отвечает, но трафик через него не идёт", now - 60_000),
            FailoverAttempt("Германия", "нет ответа · время истекло", now - 30_000)), "30 с")
        return DesktopScreenPreview(TunnelSnapshot(TunnelStatus.RECONNECTING),
            { it.copy(tried = report, healthMessage = "Нет связи", healthFailures = 2) })
    }

    @Test fun offWide() = render("win-off-wide", 1440, 900, preview = off)
    @Test fun offNarrow() = render("win-off-1024", 1024, 720, preview = off)
    @Test fun off1100() = render("win-off-1100", 1100, 760, preview = off)
    @Test fun connectingWide() = render("win-connecting-wide", 1440, 900, preview = connecting)
    @Test fun connectedWide() = render("win-connected-wide", 1440, 900, preview = connected)
    @Test fun connectedNarrow() = render("win-connected-1024", 1024, 720, preview = connected)
    @Test fun holdingWide() = render("win-holding-wide", 1440, 900, preview = holding())
    @Test fun holdingNarrow() = render("win-holding-1024", 1024, 720, preview = holding())
    @Test fun failedWide() = render("win-error-wide", 1440, 900, preview = failed)
    @Test fun failedNarrow() = render("win-error-1024", 1024, 720, preview = failed)
    @Test fun noConfigs() = render("win-no-configs", 1280, 800, StoredState(), off)
    @Test fun rules() = render("win-rules", 1440, 900, preview = off.copy(page = "ROUTES"))
    @Test fun journal() = render("win-journal", 1440, 900, preview = connected.copy(page = "DIAGNOSTICS"))
    @Test fun settings() = render("win-settings", 1440, 900, preview = off.copy(page = "SETTINGS"))
    @Test fun settingsNarrow() = render("win-settings-1024", 1024, 720, preview = off.copy(page = "SETTINGS"))
}
