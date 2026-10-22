package app.lernet.ui.audit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lernet.config.model.Group
import app.lernet.config.model.Profile
import app.lernet.config.model.ProfileSource
import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveConnStatus
import app.lernet.engine.live.LiveVia
import app.lernet.ui.config.ConfigEditorScreen
import app.lernet.ui.config.ConfigEditorUiState
import app.lernet.ui.diag.DiagScreen
import app.lernet.ui.diag.DiagUiState
import app.lernet.ui.home.ConfigDrawer
import app.lernet.ui.home.DrawerActions
import app.lernet.ui.importcfg.ImportScreen
import app.lernet.ui.importcfg.ImportUiState
import app.lernet.ui.settings.SettingsScreen
import app.lernet.ui.settings.SettingsUiState
import app.lernet.ui.theme.LerNetTheme
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The same screens the UI audit photographed (its AuditScreensTest), after UI v3.
 * Pictures go to build/reports/ui/audit; they are reviewed by eye, nothing is compared pixel by pixel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h2000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiV3ScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun profile(id: String, name: String) = Profile(
        id = id, name = name, createdAtEpochMs = 0, updatedAtEpochMs = 0, source = ProfileSource.VLESS,
        selectedOutboundId = "main", outbounds = emptyList(), subscriptionUrl = null, lastRefreshEpochMs = null,
    )
    private val profiles = listOf(profile("nl", "Нидерланды"), profile("de", "Германия"), profile("fi", "Финляндия"), profile("us", "США"))
    private val groups = listOf(Group("work", "Работа", listOf("nl", "de")))

    private fun shot(name: String) {
        val file = File("build/reports/ui/audit/$name.png")
        file.parentFile?.mkdirs()
        rule.waitForIdle()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun settingsBasic() {
        rule.setContent { LerNetTheme { SettingsScreen(SettingsUiState(), {}, {}, {}) } }
        rule.onNodeWithTag("settings_apps").assertExistsCompat()
        shot("android-settings-basic")
    }

    @Test fun settingsAdvanced() {
        rule.setContent { LerNetTheme { SettingsScreen(SettingsUiState(), {}, {}, {}) } }
        rule.onNodeWithTag("settings_advanced_toggle").performClick()
        rule.onNodeWithTag("settings_experts").assertExistsCompat()
        shot("android-settings-advanced")
    }

    private fun conn(id: String, app: String, host: String, via: LiveVia, rule: String?, status: LiveConnStatus) = LiveConn(
        id = id, app = app, uid = 10123, destHost = host, destPort = 443, domain = host, outbound = if (via == LiveVia.PROXY) "proxy" else "direct",
        via = via, uplink = 2400, downlink = 81000, rule = rule, status = status, protocol = "tls", transport = "tcp", createdAt = 1_700_000_000_000,
    )

    private val rows = listOf(
        conn("1", "org.telegram.messenger", "149.154.167.50", LiveVia.PROXY, "Иначе", LiveConnStatus.OPEN),
        conn("2", "com.android.chrome", "youtube.com", LiveVia.PROXY, "YouTube", LiveConnStatus.CLOSED),
        conn("3", "ru.sberbankmobile", "online.sberbank.ru", LiveVia.DIRECT, "Банки напрямую", LiveConnStatus.OPEN),
        conn("4", "com.android.chrome", "ya.ru", LiveVia.DIRECT, "Страна: RU", LiveConnStatus.UNFINISHED),
    )

    @Test fun diagWithRows() {
        rule.setContent { LerNetTheme { DiagScreen(DiagUiState(rows = rows, vpnUp = true, dnsOk = true), {}, {}, emptyFlow()) } }
        rule.onNodeWithTag("journal_search").assertExistsCompat()
        shot("android-diag-rows")
    }

    @Test fun diagFilteredDirect() {
        rule.setContent { LerNetTheme { DiagScreen(DiagUiState(rows = rows, vpnUp = true, dnsOk = true), {}, {}, emptyFlow()) } }
        rule.onNodeWithTag("journal_filter_direct").performClick()
        shot("android-diag-filter-direct")
    }

    @Test fun diagDetailsSheet() {
        rule.setContent { LerNetTheme { DiagScreen(DiagUiState(rows = rows, vpnUp = true, dnsOk = true), {}, {}, emptyFlow()) } }
        rule.onNodeWithText("youtube.com · правило «YouTube»", substring = true).performClick()
        rule.onNodeWithTag("journal_details").assertExistsCompat()
        shot("android-diag-details")
    }

    @Test fun importEmpty() {
        rule.setContent { LerNetTheme { ImportScreen(ImportUiState(), {}, {}, emptyFlow(), {}) } }
        rule.onNodeWithTag("import_from_qr").assertExistsCompat()
        shot("android-import")
    }

    @Test fun configEditor() {
        rule.setContent {
            LerNetTheme {
                ConfigEditorScreen(
                    ConfigEditorUiState(loading = false, profileName = "Нидерланды", server = "nl.example.net", port = "443",
                        sni = "www.microsoft.com", hasTransport = true, transportType = "xhttp", mode = "auto",
                        hasTls = true, fingerprint = "chrome"),
                    {}, {}, {}, MutableSharedFlow(),
                )
            }
        }
        shot("android-config-editor")
    }

    @Test fun drawer() {
        val noop1: (String) -> Unit = {}
        val actions = DrawerActions(noop1, {}, noop1, {}, {}, noop1, noop1, noop1, noop1, noop1, noop1, noop1, { _, _ -> }, {},
            { _, _, _ -> }, { _, _ -> }, noop1, { _, _ -> }, { _, _ -> }, { _, _ -> })
        rule.setContent {
            LerNetTheme {
                ModalDrawerSheet { ConfigDrawer(profiles, groups, "nl", emptyMap(), actions) }
            }
        }
        shot("android-drawer")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() { assertExists() }
}
