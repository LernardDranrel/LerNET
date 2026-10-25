package app.lernet.desktop

import app.lernet.engine.text.SettingsWords
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import app.lernet.config.net.LocalProxyAccess
import app.lernet.engine.policy.FailoverAttempt
import app.lernet.engine.policy.FailoverReport
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test

/** Mockup 03 / 10 on Windows: permanent config column, round button in the centre, cards with real data only. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopHomeV3RenderTest {
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
                StoredRule("r3", "grp_work", sortIndex = 2, title = "Telegram с телефона", apps = listOf("org.telegram.messenger")),
            ),
            selectedProfileId = "nl",
        )
    }

    private fun theme(content: @Composable () -> Unit) = @Composable {
        MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
            Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) { content() }
        }
    }

    @Test
    fun `home has config column, centre button and real right cards`() {
        val dir = Files.createTempDirectory("lernet-home-v3")
        DesktopStore(dir).save(sample())
        val controller = DesktopController(DesktopStore(dir))
        val scene = ImageComposeScene(1440, 900, Density(1f), content = theme {
            DesktopScreen(controller, {}, {}, topChrome = {
                DesktopModeTabs(false, {}, reducedMotion = true)
            })
        })
        try {
            shot(scene, "windows-v3-home")
            val all = texts(scene)
            // m1: «Правила» in the config line, one attention zone, «Журнал запросов» row instead of recent requests.
            listOf("Конфиги · 4", "Работа", "Дом", "Отключено", "Правила", "Журнал запросов",
                "Пропущенные условия", "Анализ сети ↗").forEach { assertThat(all).contains(it) }
            assertThat(all).doesNotContain("Последние запросы")
            assertThat(all).doesNotContain("Режим подключения")
            assertThat(all.any { "Telegram с телефона" in it && "не работает" in it }).isTrue()
        } finally { scene.close(); controller.close() }
    }

    @Test
    fun `holding shows what was tried, another folder and switch off`() {
        val dir = Files.createTempDirectory("lernet-home-hold")
        DesktopStore(dir).save(sample())
        val controller = DesktopController(DesktopStore(dir))
        val now = System.currentTimeMillis()
        val report = FailoverReport("Работа", listOf(
            FailoverAttempt("Нидерланды", "сервер отвечает, но трафик через него не идёт", now - 60_000),
            FailoverAttempt("Германия", "нет ответа · время истекло", now - 30_000)), "30 с")
        val history = listOf(
            LiveConnection("1", "corp.example", "10.0.0.1:443", "chrome.exe", "rule", listOf("proxy"), 0, 0, "", "tcp", lastSeenAt = now),
            LiveConnection("2", "bank.example", "10.0.0.2:443", "msedge.exe", "rule", listOf("direct"), 0, 0, "", "tcp", lastSeenAt = now - 1000))
        val scene = ImageComposeScene(1124, 900, Density(1f), content = theme {
            Row(Modifier.fillMaxSize().padding(22.dp)) {
                ProfileHome(controller.state.value.saved, TunnelSnapshot(TunnelStatus.RUNNING), false, emptyMap(), null, TraceSnapshot(),
                    controller, {}, localProxy = null, tried = report, diagnostics = CoreDiagnostics(connections = history),
                    onOpenRules = {}, onOpenJournal = {})
            }
        })
        try {
            shot(scene, "windows-v3-home-holding")
            val all = texts(scene)
            listOf("Нет связи · VPN держим", "Что пробовали", "Конфиг из другой папки", "Выключить VPN").forEach {
                assertThat(all).contains(it)
            }
            // The switch-off warning says the traffic would go direct.
            assertThat(all.any { "Выключить VPN" in it && "напрямую" in it }).isTrue()
        } finally { scene.close(); controller.close() }
    }

    @Test
    fun `settings carry the mode and the copyable proxy`() {
        val dir = Files.createTempDirectory("lernet-settings-proxy")
        DesktopStore(dir).save(sample().copy(mode = "PROXY"))
        val controller = DesktopController(DesktopStore(dir))
        val scene = ImageComposeScene(1124, 900, Density(1f), content = theme {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Settings(controller.state.value.saved, controller, {}, localProxy = LocalProxyAccess(2080, "lernet-a1b2", "Xk29-fixture-password-7"))
            }
        })
        try {
            shot(scene, "windows-v3-settings-proxy")
            val all = texts(scene)
            listOf(SettingsWords.MODE, SettingsWords.MODE_PROXY, "127.0.0.1:2080", "lernet-a1b2", "Копировать").forEach { assertThat(all).contains(it) }
        } finally { scene.close(); controller.close() }
    }

    private fun shot(scene: ImageComposeScene, name: String) {
        var t = 0L
        repeat(4) { scene.render(t).close(); t += 500_000_000L }
        scene.render(t).use { image ->
            val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
            File("build/expert-ui-review").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
        }
    }

    private fun texts(scene: ImageComposeScene): List<String> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
    }
    @Test
    fun `vpn sidebar keeps config column with subscription folder`() {
        val subUrl = "https://example.com/sub"
        fun profile(id: String, name: String, group: String?, subscriptionUrl: String? = null) = StoredProfile(
            id, name, "VLESS",
            listOf(StoredOutbound("o-$id", "proxy", "vless",
                """{"type":"vless","tag":"proxy","server":"$id.example.net","server_port":443,"uuid":"00000000-0000-4000-8000-000000000000"}""")),
            "o-$id", groupId = group, subscriptionUrl = subscriptionUrl,
        )
        val state = StoredState(
            groups = listOf(
                StoredGroup("work", "Работа", autoSwap = true),
                StoredGroup("sub", "Подписка · example.com", autoSwap = true, subscriptionUrl = subUrl),
            ),
            profiles = listOf(
                profile("nl", "Нидерланды", "work"),
                profile("de", "Германия", "work"),
                profile("sg", "Сингапур", "sub", subUrl),
                profile("jp", "Токио", "sub", subUrl),
            ),
            selectedProfileId = "nl",
        )
        val dir = Files.createTempDirectory("lernet-vpn-sub")
        DesktopStore(dir).save(state)
        val controller = DesktopController(DesktopStore(dir))
        val scene = ImageComposeScene(1440, 900, Density(1f), content = theme {
            DesktopScreen(controller, {}, {}, topChrome = {
                DesktopModeTabs(false, {}, reducedMotion = true)
            })
        })
        try {
            shot(scene, "windows-vpn-sidebar-subscription")
            File("/workspace/projects/LerNET-ui3/out/vpn-sidebar").mkdirs()
            File("build/expert-ui-review/windows-vpn-sidebar-subscription.png")
                .copyTo(File("/workspace/projects/LerNET-ui3/out/vpn-sidebar/windows-vpn-sidebar-subscription.png"), overwrite = true)
            val all = texts(scene)
            listOf("Конфиги · 4", "Работа", "Подписка · example.com", "Сингапур", "Токио", "Анализ сети ↗").forEach {
                assertThat(all).contains(it)
            }
        } finally {
            scene.close()
            controller.close()
        }
    }
}
