package app.lernet.ui.expert

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.ui.theme.LerNetTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpertChromeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val bundle = TransferBundle(scope = "all", groups = emptyList(), profiles = emptyList(), rules = emptyList())

    @Test
    fun bottomMenuHasTheFiveExpertSectionsInOrder() {
        assertThat(ExpertBottomSections.map { rule.activity.getString(it.label) })
            .containsExactly("Пульт", "Схема", "Конфиги", "Проверка пути", "События").inOrder()
    }

    @Test
    fun bottomMenuSwitchesSectionsAndFrameIsDrawn() {
        var section by mutableStateOf(ExpertSection.OVERVIEW)
        var settings = 0
        rule.setContent {
            LerNetTheme {
                ExpertScreen(
                    ExpertUiState(ExpertRuntimeState(saved = NetworkPolicy(), phase = ExpertSessionPhase.RUNNING, desiredEnabled = true, runningSinceEpochMs = System.currentTimeMillis() - ((14 * 24 + 6) * 60 + 5) * 60_000L, tun = app.lernet.engine.policy.TunIdentity("tun"), appliedRevision = 0), bundle),
                    remember { SnackbarHostState() }, {}, {}, {}, {}, {}, false,
                    selectedSection = section, onSectionChange = { section = it },
                    onSettings = { settings++ },
                )
            }
        }
        rule.onNodeWithTag("expert_frame").assertExists()
        rule.onNodeWithTag("expert_bottom_menu").assertIsDisplayed()
        rule.onNodeWithTag("expert_tab_overview").assertIsSelected()
        rule.onNodeWithTag("expert_uptime").assertExists()
        rule.onNodeWithText("14 дн 06 ч").assertExists()
        capture("android-expert-v2-pult.png")
        rule.onNodeWithTag("expert_tab_schema").performClick()
        rule.runOnIdle { assertThat(section).isEqualTo(ExpertSection.SCHEMA) }
        capture("android-expert-v2-schema.png")
        rule.onNodeWithTag("expert_tab_exits").performClick()
        rule.runOnIdle { assertThat(section).isEqualTo(ExpertSection.EXITS) }
        capture("android-expert-v2-configs.png")
        rule.onNodeWithTag("expert_tab_simulation").performClick()
        rule.runOnIdle { assertThat(section).isEqualTo(ExpertSection.SIMULATION) }
        capture("android-expert-v2-rules.png")
        // N3: «События» is the fifth section of the bottom menu, no ⓘ in the top bar.
        rule.onNodeWithTag("expert_tab_journal").performClick()
        rule.runOnIdle { assertThat(section).isEqualTo(ExpertSection.JOURNAL) }
        capture("android-expert-v2-events.png")
        // Journal + ⚙ always in Expert top bar (same as VPN); settings are not ⋮-only.
        rule.onNodeWithTag("expert_diag").assertIsDisplayed()
        rule.onNodeWithTag("expert_settings").assertIsDisplayed()
        rule.onNodeWithTag("expert_settings").performClick()
        rule.runOnIdle { assertThat(settings).isEqualTo(1) }
        rule.onNodeWithTag("expert_more").performClick()
        rule.onNodeWithTag("expert_menu_safety").assertIsDisplayed()
    }

    @Test
    fun expertTopBarOpensDiagAndSettings() {
        var settings = 0
        var diag = 0
        rule.setContent {
            LerNetTheme {
                ExpertScreen(
                    ExpertUiState(ExpertRuntimeState(saved = NetworkPolicy(), phase = ExpertSessionPhase.RUNNING, desiredEnabled = true, runningSinceEpochMs = System.currentTimeMillis(), tun = app.lernet.engine.policy.TunIdentity("tun"), appliedRevision = 0), bundle),
                    remember { SnackbarHostState() }, {}, {}, {}, {}, {}, false,
                    onSettings = { settings++ },
                    onOpenDiag = { diag++ },
                )
            }
        }
        rule.onNodeWithTag("expert_diag").performClick()
        rule.onNodeWithTag("expert_settings").performClick()
        rule.runOnIdle {
            assertThat(diag).isEqualTo(1)
            assertThat(settings).isEqualTo(1)
        }
    }

    @Test
    fun splashShowsOnlyOnFirstEntry() {
        val context: Context = rule.activity.applicationContext
        context.getSharedPreferences("lernet_ui", Context.MODE_PRIVATE).edit().clear().commit()
        rule.mainClock.autoAdvance = false
        var first: ExpertSplashState? = null
        rule.setContent {
            LerNetTheme {
                val s = rememberExpertSplash(context)
                first = s
                if (s.visible) ExpertSplash()
            }
        }
        rule.mainClock.advanceTimeBy(950)
        rule.onNodeWithTag("expert_splash").assertExists()
        rule.onNodeWithText("Эксперт").assertExists()
        capture("android-expert-v2-splash.png")
        assertThat(first!!.firstEntry).isTrue()
        rule.mainClock.advanceTimeBy(EXPERT_SPLASH_MS + 100)
        rule.onNodeWithTag("expert_splash").assertDoesNotExist()
        assertThat(ExpertSplashMemory.seen(context)).isTrue()
    }

    private fun capture(name: String) {
        val file = File("build/reports/ui/$name")
        file.parentFile?.mkdirs()
        rule.waitForIdle()
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
