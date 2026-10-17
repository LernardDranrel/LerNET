package app.lernet.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lernet.config.net.LocalProxyAccess
import app.lernet.engine.RunMode
import app.lernet.settings.AppSettings
import app.lernet.ui.theme.LerNetTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v2: the VPN/proxy choice lives in Settings; the running proxy is copyable there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h1100dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsProxyScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsCarryModeAndRunningProxy() {
        rule.setContent {
            LerNetTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        settings = AppSettings(mode = RunMode.PROXY),
                        liveProxy = LocalProxyAccess(2080, "lernet-a1b2", "Xk29-fixture-password-7"),
                        sessionActive = true,
                    ),
                    onIntent = {}, onBack = {}, onShareLogs = {}, onOpenDiag = {},
                )
            }
        }
        rule.onNodeWithTag("settings_run_mode").assertIsDisplayed()
        rule.onNodeWithText("127.0.0.1:2080", substring = true).assertExists()
        val file = File("build/reports/ui/mobile-settings-proxy.png")
        file.parentFile?.mkdirs()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
