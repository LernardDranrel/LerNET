package app.lernet.ui.network

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import app.lernet.ui.theme.LerNetTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Detached Compose fixture: no LerNetApp, engine, collector, VPN or live network calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkObservationScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val snapshot = AndroidNetworkCollector {
        listOf(AndroidNetworkRecord("fixture-wifi", "wlan0", listOf("Wi-Fi"), true, true, true, false, false,
            listOf("192.0.2.10/24"), listOf("1.1.1.1"), listOf("0.0.0.0/0" to "192.0.2.1"), 1500, "Не указан", "Активен"))
    }.collect { 1000L }

    @Test fun pathExplorationExposesEvidenceAndCanShowChangesWithoutAnEngine() {
        rule.setContent { LerNetTheme { NetworkObservationScreen(NetworkObservationUiState(snapshot = snapshot), {}, {}, {}, {}) } }
        rule.onNodeWithText("Разберём, куда идёт интернет").assertIsDisplayed()
        capture("android-network-overview.png")
        rule.onNodeWithText("Имена сайтов").performScrollTo().performClick()
        rule.onNodeWithText("1.1.1.1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Изменения").performScrollTo().performClick()
        rule.onNodeWithText("Нужны два снимка.", substring = true).assertIsDisplayed()
        capture("android-network-changes.png")
    }

    @Test fun externalObserverIsContactedOnlyAfterExplicitConfirmation() {
        var checks = 0
        rule.setContent { LerNetTheme { NetworkObservationScreen(NetworkObservationUiState(snapshot = snapshot), {}, {}, { checks++ }, {}) } }
        // This item starts outside LazyColumn's composed viewport. Find it through the list,
        // then interact with the actual button; no callback bypasses the confirmation dialog.
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Проверить внешний IP"))
        rule.onNodeWithText("Проверить внешний IP").performScrollTo().performClick()
        rule.onNodeWithText("Проверить внешний IP?").assertIsDisplayed()
        rule.runOnIdle { assertThat(checks).isEqualTo(0) }
        rule.onNodeWithText("Проверить", substring = false).performClick()
        rule.runOnIdle { assertThat(checks).isEqualTo(1) }
    }

    private fun capture(name: String) {
        val file = File("build/reports/ui/$name")
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
