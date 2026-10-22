package app.lernet.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.lernet.config.model.Group
import app.lernet.config.model.Profile
import app.lernet.config.model.ProfileSource
import app.lernet.engine.ConnectionCause
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.ui.theme.LerNetTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every home state on a phone, saved for the review in build/reports/ui/states/. Tall variants show the whole scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeStatesScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun profile(id: String, name: String) = Profile(
        id = id, name = name, createdAtEpochMs = 0, updatedAtEpochMs = 0, source = ProfileSource.VLESS,
        selectedOutboundId = "main", outbounds = emptyList(), subscriptionUrl = null, lastRefreshEpochMs = null,
    )

    private val nl = profile("nl", "Нидерланды")
    private val de = profile("de", "Германия")
    private val fi = profile("fi", "Финляндия")
    private val solo = profile("solo", "Личный")

    // The same data as DesktopStatesRenderTest (Windows), so the pairs of screenshots compare honestly.
    private val group = Group("work", "Работа", listOf("nl", "de"), autoFailover = true)
    private val home = Group("home", "Дом", listOf("fi"))

    private fun base(snapshot: ConnectionSnapshot, probes: Map<String, ProfileProbe> = emptyMap()) = HomeUiState(
        snapshot = snapshot, activeProfile = nl, profiles = listOf(nl, de, fi, solo), groups = listOf(group, home),
        engineAvailable = true, loading = false, probes = probes,
    )

    /** «Сервер: отвечает · 38 мс», «Интернет через VPN: работает · 142 мс» — on both platforms. */
    private val good = mapOf("nl" to ProfileProbe(tcpMs = 38, reachable = true, tunnelMs = 142, tunnelChecked = true))

    private fun render(name: String, state: HomeUiState) {
        rule.setContent {
            LerNetTheme {
                // As in the app (LerNetAppRoot passes it): the VPN | Эксперт switch is on the home, as on Windows.
                HomeScreen(state = state, onIntent = {}, onOpenDrawer = {}, onOpenSettings = {}, onOpenDiag = {},
                    onOpenRoutes = {}, onRefreshHop = {}, onOpenExpert = {})
            }
        }
        save(name)
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val file = File("build/reports/ui/states/$name.png")
        file.parentFile?.mkdirs()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val texts = texts()
        listOf("TCP", "HTTPS", "B/s", "KiB", "Exception", "null.", "хоп").forEach { word ->
            assertThat(texts.filter { word in it }.map { "$name: $it" }).isEmpty()
        }
        // One action per state: «Выключить VPN» replaces the «Отключить» caption, «Повторить» replaces «Подключить».
        if ("Выключить VPN" in texts) assertThat(texts.filter { it == "Отключить" }.map { "$name: $it" }).isEmpty()
        if ("Повторить" in texts) assertThat(texts.filter { it == "Подключить" }.map { "$name: $it" }).isEmpty()
    }

    private fun texts(): List<String> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
    }

    @Test
    fun off() = render("phone-off", base(ConnectionSnapshot.idle()))

    @Test
    fun connecting() = render("phone-connecting",
        base(ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTING, activeProfileId = "nl")))

    @Test
    fun connected() = render("phone-connected", base(
        ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl", serverTcpMs = 38,
            uplinkBps = 2048, downlinkBps = 51_200, uplinkTotal = 120_000, downlinkTotal = 2_400_000),
        mapOf("nl" to ProfileProbe(tcpMs = 38, reachable = true, tunnelMs = 142, tunnelChecked = true)),
    ))

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun connectedWholeScroll() = render("phone-connected-full", base(
        ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl", serverTcpMs = 38),
        good,
    ))

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun offWholeScroll() = render("phone-off-full", base(ConnectionSnapshot.idle(), good))

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun connectingWholeScroll() = render("phone-connecting-full",
        base(ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTING, activeProfileId = "nl"), good))

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun holdingWholeScroll() {
        val now = System.currentTimeMillis()
        val report = app.lernet.engine.policy.FailoverReport("Работа", listOf(
            app.lernet.engine.policy.FailoverAttempt("Нидерланды", "нет ответа · время истекло", now - 60_000),
            app.lernet.engine.policy.FailoverAttempt("Германия", "сервер отклонил соединение", now)), "1 мин 10 с")
        render("phone-holding-full", base(ConnectionSnapshot.idle().copy(state = ConnectionState.RECONNECTING,
            activeProfileId = "nl", tried = report)).copy(skippedRules = listOf("Блокнот на работе")))
    }

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun jammedWholeScroll() = render("phone-hold-jammed-full", base(
        ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl", serverTcpMs = 38,
            hold = app.lernet.engine.policy.LinkHold.JAMMED),
        mapOf("nl" to ProfileProbe(tcpMs = 38, reachable = true, tunnelChecked = true)),
    ))

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun allSilentWholeScroll() = render("phone-hold-silent-full", base(
        ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl", serverTcpMs = 38,
            hold = app.lernet.engine.policy.LinkHold.ALL_SILENT, nextCheckAtMs = System.currentTimeMillis() + 95_000),
        mapOf("nl" to ProfileProbe(tcpMs = 38, reachable = true, tunnelChecked = true)),
    ))

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun budgetWaitWholeScroll() = render("phone-budget-wait-full", base(
        ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl", serverTcpMs = 38),
        mapOf("nl" to ProfileProbe(running = true, tcpMs = 38, reachable = true, budgetWaitMs = 42_000)),
    ))

    /** The strip above every secondary screen in the hold states added by the resilience branch. */
    @Test
    fun holdStrips() {
        val snapshots = listOf(
            ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl", dnsOk = true),
            ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl",
                hold = app.lernet.engine.policy.LinkHold.JAMMED),
            ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl",
                hold = app.lernet.engine.policy.LinkHold.ALL_SILENT),
            ConnectionSnapshot.idle().copy(state = ConnectionState.RECONNECTING, activeProfileId = "nl",
                tried = app.lernet.engine.policy.FailoverReport("Работа", emptyList(), "30 с")),
        )
        rule.setContent {
            LerNetTheme {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                            androidx.compose.ui.unit.Dp(12f)),
                    ) {
                        snapshots.forEach { snap ->
                            app.lernet.ui.nav.StatusStrip(app.lernet.ui.nav.stripStatus(snap, "Нидерланды", null), onClick = {})
                        }
                    }
                }
            }
        }
        save("phone-strips-hold")
        val texts = texts()
        assertThat(texts).contains("Связь глушат · держим подключение")
        assertThat(texts).contains("Серверы не отвечают · VPN держим")
        assertThat(texts).contains("Нет связи · VPN держим")
    }

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun failedWholeScroll() = render("phone-error-full", base(ConnectionSnapshot.idle().copy(
        state = ConnectionState.FAILED, activeProfileId = "nl",
        cause = ConnectionCause.DialFailure("dial tcp 203.0.113.5:443: connect: connection refused"))))

    @Test
    fun failedTimeout() = render("phone-error-timeout", base(ConnectionSnapshot.idle().copy(
        state = ConnectionState.FAILED, activeProfileId = "nl", cause = ConnectionCause.DialTimeout(15))))

    @Test
    @Config(qualifiers = "w390dp-h2000dp-xxhdpi")
    fun proxyConnectedWholeScroll() = render("phone-proxy-connected-full", base(
        ConnectionSnapshot.idle().copy(state = ConnectionState.CONNECTED, activeProfileId = "nl", serverTcpMs = 38,
            mode = app.lernet.engine.RunMode.PROXY),
        mapOf("nl" to ProfileProbe(tcpMs = 38, reachable = true, tunnelMs = 142, tunnelChecked = true)),
    ).copy(settings = app.lernet.settings.AppSettings(mode = app.lernet.engine.RunMode.PROXY),
        proxyAccess = app.lernet.config.net.LocalProxyAccess(2080, "lernet-a1b2", "Xk29-fixture-password-7")))

    @Test
    fun noConfigs() = render("phone-no-configs", HomeUiState(engineAvailable = true, loading = false))

    @Test
    fun drawer() {
        // The drawer has an endless animation; time is advanced by hand so the rule does not wait for it to stop.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LerNetTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box {
                        ConfigDrawer(
                            profiles = listOf(nl, de, fi), groups = listOf(group), activeProfileId = "nl",
                            probes = mapOf("nl" to ProfileProbe(tcpMs = 38, reachable = true, tunnelMs = 142, tunnelChecked = true),
                                "de" to ProfileProbe(tcpMs = null, reachable = false)),
                            actions = DrawerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
                                { _, _, _ -> }, { _, _ -> }, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        save("phone-drawer")
    }
}
