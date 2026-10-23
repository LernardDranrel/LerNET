package app.lernet.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    // The same data as the phone's HomeStatesScreenshotTest: 38 ms to the server, 142 ms through the VPN.
    private val goodProbes = mapOf(
        "nl" to ProbeResult(latencyMs = 38, tunnelLatencyMs = 142, tunnelMessage = "Работает"),
        "de" to ProbeResult(latencyMs = 51, tunnelMessage = app.lernet.engine.policy.LinkCheck.notMeasured(app.lernet.engine.policy.LinkCheck.FullPath.ON_SWITCH)!!),
        "fi" to ProbeResult(latencyMs = null, message = "нет ответа"),
    )

    private fun theme(content: @Composable () -> Unit) = @Composable {
        MaterialTheme(colorScheme = desktopColors, typography = desktopTypography) {
            Surface(color = desktopColors.background, contentColor = desktopColors.onBackground) { content() }
        }
    }

    private fun render(
        name: String,
        width: Int,
        height: Int,
        state: StoredState = sample(),
        preview: DesktopScreenPreview,
        setup: (DesktopController) -> Unit = {},
    ) {
        // An administrator window: VPN mode, as on the phone (on Linux the token check says «not elevated»).
        WindowsElevation.overrideForTests = true
        val dir = Files.createTempDirectory("lernet-states")
        DesktopStore(dir).save(state)
        val controller = DesktopController(DesktopStore(dir))
        setup(controller)
        // The strip shows the same status as the real window (G1), computed from the same preview state.
        val stripUi = preview.ui?.invoke(controller.state.value) ?: controller.state.value
        val status = workspaceStatus(null, preview.tunnel ?: TunnelSnapshot(TunnelStatus.STOPPED), stripUi)
        val scene = ImageComposeScene(width, height, Density(1f), content = theme {
            // As the app: mode strip in the right column; no strip status over the basic home.
            DesktopScreen(controller, {}, {}, preview = preview, topChrome = {
                DesktopModeTabs(false, {}, status = status.takeIf { preview.page != null && preview.page != "PROFILES" }, reducedMotion = true)
            })
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
                // The strip on top repeats the state on purpose (G1); the home itself writes it once.
                states.forEach { s -> assertThat(all.count { it == s }).isAtMost(2) }
                // One action per state: while holding only «Выключить VPN», never a second «Отключить» caption.
                if ("Нет связи · VPN держим" in all) {
                    assertThat(all.filter { it == "Отключить" }.map { "$name: $it" }).isEmpty()
                    assertThat(all.count { it == "Выключить VPN" }).isEqualTo(1)
                }
            }
        } finally { scene.close(); controller.close(); WindowsElevation.overrideForTests = null }
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
        { it.copy(probes = goodProbes, healthMessage = "VPN отвечает · 142 мс", healthVerified = true, tunnelLatencyMs = 142) })
    // The phone's failedWholeScroll: the server refused the connection; the raw line is behind «Подробнее».
    private val refused = "dial tcp 203.0.113.5:443: connect: connection refused"
    private val failed = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.FAILED, refused),
        { it.copy(probes = goodProbes, connectionError = ConnectionError(app.lernet.engine.text.CoreFailureWords.title(refused))) })
    private fun holding(): DesktopScreenPreview {
        val now = System.currentTimeMillis()
        val report = FailoverReport("Работа", listOf(
            FailoverAttempt("Нидерланды", "сервер отвечает, но трафик через него не идёт", now - 60_000),
            FailoverAttempt("Германия", "нет ответа · время истекло", now - 30_000)), "30 с")
        return DesktopScreenPreview(TunnelSnapshot(TunnelStatus.RECONNECTING),
            { it.copy(tried = report, healthMessage = "Нет связи", healthFailures = 2, probes = heldProbes) })
    }

    // While held, the live check through the VPN has no answer: no «работает · 128 мс» in these previews.
    private val heldProbes = goodProbes + ("nl" to ProbeResult(latencyMs = 38, tunnelMessage = "Нет ответа"))
    private val jammed = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.RUNNING, "Подключено"), {
        it.copy(probes = heldProbes, healthVerified = false, hold = app.lernet.engine.policy.LinkHold.JAMMED,
            healthMessage = app.lernet.engine.policy.LinkJudge.holdMessage(app.lernet.engine.policy.LinkHold.JAMMED))
    })
    private val silent = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.RUNNING, "Подключено"), {
        it.copy(probes = heldProbes, healthVerified = false, hold = app.lernet.engine.policy.LinkHold.ALL_SILENT,
            healthMessage = app.lernet.engine.policy.LinkJudge.holdMessage(app.lernet.engine.policy.LinkHold.ALL_SILENT, app.lernet.engine.policy.RecheckJitter.label(94_000)))
    })
    private val budgetWait = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.RUNNING, "Подключено"), {
        it.copy(probes = goodProbes + ("nl" to ProbeResult(latencyMs = 38, checking = true, budgetWaitMs = 42_000)),
            healthMessage = "VPN отвечает · 142 мс", healthVerified = true, tunnelLatencyMs = 142)
    })
    private val errorBar = DesktopScreenPreview(TunnelSnapshot(TunnelStatus.FAILED, "VPN не запустился"), {
        it.copy(probes = goodProbes, message = "Сервер не принял подключение. Проверьте конфиг или выберите другой.",
            connectionError = ConnectionError("Сервер не принял подключение. Проверьте конфиг или выберите другой.", "nl"))
    })

    @Test fun jammedWide() = render("win-hold-jammed-wide", 1440, 900, preview = jammed)
    @Test fun silentWide() = render("win-hold-silent-wide", 1440, 900, preview = silent)
    @Test fun silentNarrow() = render("win-hold-silent-1024", 1024, 720, preview = silent)
    @Test fun budgetWaitWide() = render("win-budget-wait-wide", 1440, 900, preview = budgetWait)
    @Test fun errorBarWide() = render("win-error-bar-wide", 1440, 900, preview = errorBar)
    @Test fun deletedBar() = render("win-deleted-bar", 1440, 900, preview = off) { it.delete("solo") }
    @Test fun journalTechnical() = render("win-journal-1024", 1024, 720, preview = connected.copy(page = "DIAGNOSTICS"))
    @Test fun offWide() = render("win-off-wide", 1440, 900, preview = off)
    @Test fun offNarrow() = render("win-off-1024", 1024, 720, preview = off)
    @Test fun off1100() = render("win-off-1100", 1100, 760, preview = off)
    @Test fun connectingWide() = render("win-connecting-wide", 1440, 900, preview = connecting)
    @Test fun connectedWide() = render("win-connected-wide", 1440, 900, preview = connected)
    @Test fun connectedNarrow() = render("win-connected-1024", 1024, 720, preview = connected)
    // As the phone's holding fixture: one branch this platform skips («Ещё 1» under the report).
    private fun withSkipped() = sample().let { it.copy(rules = it.rules +
        StoredRule("r3", "grp_work", sortIndex = 2, title = "Блокнот на работе", apps = listOf("com.example.notes"))) }
    @Test fun holdingWide() = render("win-holding-wide", 1440, 900, withSkipped(), holding())
    @Test fun holdingNarrow() = render("win-holding-1024", 1024, 720, withSkipped(), holding())
    @Test fun failedWide() = render("win-error-wide", 1440, 900, preview = failed)
    @Test fun failedNarrow() = render("win-error-1024", 1024, 720, preview = failed)
    /** The pair of the phone's proxy-connected-full: the same note under the config line. */
    @Test fun proxyConnected() = render("win-proxy-connected-wide", 1440, 900, sample().copy(mode = "PROXY"), connected)
    @Test fun noConfigs() = render("win-no-configs", 1280, 800, StoredState(), off)
    @Test fun rules() = render("win-rules", 1440, 900, preview = off.copy(page = "ROUTES"))
    @Test fun journal() = render("win-journal", 1440, 900, preview = connected.copy(page = "DIAGNOSTICS"))
    @Test fun settings() = render("win-settings", 1440, 900, preview = off.copy(page = "SETTINGS"))
    @Test fun settingsNarrow() = render("win-settings-1024", 1024, 720, preview = off.copy(page = "SETTINGS"))

    /** The TLS fingerprint field from the resilience branch, on the «Удалённый сервер» page of the config editor. */
    @Test
    fun editorFingerprint() {
        val tlsJson = """{"type":"vless","tag":"proxy","server":"nl.example.net","server_port":443,""" +
            """"uuid":"00000000-0000-4000-8000-000000000000","tls":{"enabled":true,"server_name":"www.example.com",""" +
            """"utls":{"enabled":true,"fingerprint":"chrome"}}}"""
        val profile = StoredProfile("nl", "Нидерланды", "VLESS", listOf(StoredOutbound("o-nl", "proxy", "vless", tlsJson)), "o-nl")
        val state = StoredState(profiles = listOf(profile), selectedProfileId = "nl")
        val dir = Files.createTempDirectory("lernet-editor")
        DesktopStore(dir).save(state)
        val controller = DesktopController(DesktopStore(dir))
        val scene = ImageComposeScene(860, 760, Density(1f), content = theme {
            DesktopProfileEditor(profile, controller.state.value.saved, controller, {}, embedded = true, startPage = EditorPage.REMOTE)
        })
        try {
            var t = 0L
            repeat(3) { scene.render(t).close(); t += 500_000_000L }
            scene.render(t).use { image ->
                val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                File("build/ux-states").apply { mkdirs() }.resolve("win-editor-fingerprint.png").writeBytes(bytes)
            }
            assertThat(texts(scene).joinToString("\n")).contains("Отпечаток TLS")
        } finally { scene.close(); controller.close() }
    }

    @Test
    fun journalWithRows() {
        val dir = Files.createTempDirectory("lernet-journal")
        DesktopStore(dir).save(sample())
        val controller = DesktopController(DesktopStore(dir))
        val now = System.currentTimeMillis()
        val history = listOf(
            LiveConnection("1", "mail.corp.example", "10.0.0.1:443", "chrome.exe", "rule", listOf("proxy"),
                12_000, 480_000, "", "tcp", lastSeenAt = now),
            LiveConnection("2", "bank.example", "10.0.0.2:443", "msedge.exe", "rule", listOf("direct"), 3_000, 52_000, "", "tcp",
                lastSeenAt = now - 4_000),
            LiveConnection("3", "ads.example", "10.0.0.3:443", "Telegram.exe", "rule", listOf("block"), 0, 0, "", "tcp",
                active = false, lastSeenAt = now - 9_000),
            LiveConnection("4", "api.example", "10.0.0.4:443", "", "final", listOf("proxy"), 900, 4_100, "", "udp",
                lastSeenAt = now - 15_000),
        )
        val scene = ImageComposeScene(1180, 760, Density(1f), content = theme {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Diagnostics(TunnelSnapshot(TunnelStatus.RUNNING), CoreDiagnostics(connections = history), history, controller,
                    initialSelectedId = "1")
            }
        })
        try {
            var t = 0L
            repeat(3) { scene.render(t).close(); t += 500_000_000L }
            scene.render(t).use { image ->
                val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                File("build/ux-states").apply { mkdirs() }.resolve("win-journal-rows.png").writeBytes(bytes)
            }
            val all = texts(scene)
            assertThat(all).contains("mail.corp.example")
            // «TCP» допустим только в карточке выбранной строки (как «Вид связи» на телефоне), не в самом списке.
            assertThat(all).contains("Вид связи")
            listOf("Exception", "null.").forEach { word -> assertThat(all.filter { word in it }).isEmpty() }
        } finally { scene.close(); controller.close() }
    }

    @Test
    fun importDialog() {
        val scene = ImageComposeScene(720, 640, Density(1f), content = theme {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(24.dp)) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {},
                    title = { androidx.compose.material3.Text(app.lernet.engine.text.ImportWords.TITLE) },
                    text = {
                        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Text(
                                app.lernet.engine.text.ImportWords.HINT, color = Color(0xFFA1AEC4),
                            )
                            androidx.compose.material3.OutlinedTextField(
                                "", {}, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { androidx.compose.material3.Text(app.lernet.engine.text.ImportWords.NAME) },
                                placeholder = { androidx.compose.material3.Text(app.lernet.engine.text.ImportWords.NAME_HINT) },
                            )
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp),
                            ) {
                                androidx.compose.material3.TextButton({}) {
                                    androidx.compose.material3.Text(app.lernet.engine.text.ImportWords.PASTE)
                                }
                                androidx.compose.material3.TextButton({}) {
                                    androidx.compose.material3.Text(app.lernet.engine.text.ImportWords.FROM_FILE)
                                }
                                androidx.compose.material3.TextButton({}) {
                                    androidx.compose.material3.Text(app.lernet.engine.text.ImportWords.FROM_QR)
                                }
                            }
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    "Авто",
                                    app.lernet.engine.text.ImportWords.CHIP_VLESS,
                                    app.lernet.engine.text.ImportWords.CHIP_JSON,
                                    app.lernet.engine.text.ImportWords.CHIP_URL,
                                    app.lernet.engine.text.ImportWords.CHIP_SUB,
                                ).forEach {
                                    androidx.compose.material3.FilterChip(
                                        false, {}, label = { androidx.compose.material3.Text(it) },
                                    )
                                }
                            }
                            androidx.compose.material3.OutlinedTextField(
                                "", {}, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                placeholder = {
                                    androidx.compose.material3.Text(app.lernet.engine.text.ImportWords.INPUT_EXAMPLE)
                                },
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton({}) {
                            androidx.compose.material3.Text(app.lernet.engine.text.ImportWords.ACTION)
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton({}) { androidx.compose.material3.Text("Отмена") }
                    },
                )
            }
        })
        try {
            var clock = 0L
            repeat(2) { scene.render(clock).close(); clock += 500_000_000L }
            scene.render(clock).use { image ->
                val bytes = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
                File("build/ux-states").apply { mkdirs() }.resolve("win-import.png").writeBytes(bytes)
            }
            val shown = texts(scene)
            assertThat(shown).contains(app.lernet.engine.text.ImportWords.NAME)
            assertThat(shown).contains(app.lernet.engine.text.ImportWords.FROM_QR)
            assertThat(shown).contains(app.lernet.engine.text.ImportWords.CHIP_VLESS)
        } finally {
            scene.close()
        }
    }
}
