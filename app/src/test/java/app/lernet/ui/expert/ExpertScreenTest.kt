package app.lernet.ui.expert

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.policy.VerifiedInterfaceBinding
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExpertIntent
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTree
import app.lernet.ui.theme.LerNetTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Isolated UI fixture. It never creates LerNetApp, a service, a native engine or a network request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpertScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val bundle = TransferBundle(scope = "all", groups = emptyList(), profiles = emptyList(), rules = emptyList())

    @Test
    fun compactSchemaKeepsItsRootVisibleInSmallRemainingViewport() {
        rule.setContent {
            LerNetTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    ExpertSchema(ExpertRuntimeState(saved = NetworkPolicy()), bundle, {}, Modifier.height(360.dp))
                }
            }
        }
        rule.onNodeWithText("Путь по умолчанию").assertIsDisplayed()
        rule.onNodeWithText("Справка").assertIsDisplayed()
        capture("android-expert-schema-compact.png")
    }

    @Test
    fun healthSettingsStayOpenWhenDurableWriteIsRejected() {
        var dismissed = false
        var requested: PolicyHealthSettings? = null
        rule.setContent {
            LerNetTheme {
                ExpertHealthEditor(PolicyHealthSettings(), { dismissed = true }) {
                    requested = it
                    "Ошибка записи настроек"
                }
            }
        }
        rule.onNodeWithText("✓ Готово").assertIsDisplayed().performClick()
        rule.onNodeWithText("Ошибка записи настроек").assertExists()
        capture("android-expert-health-editor.png", dialog = true)
        rule.runOnIdle {
            assertThat(dismissed).isFalse()
            assertThat(requested).isEqualTo(PolicyHealthSettings())
        }
    }

    @Test
    fun browsingExpertDoesNotEnableTrafficAndStartupCanBeCancelled() {
        var starts = 0
        val commands = mutableListOf<ExpertIntent>()
        val runtime = ExpertRuntimeState(saved = NetworkPolicy(), phase = ExpertSessionPhase.STARTING)
        rule.setContent {
            LerNetTheme {
                ExpertScreen(
                    ExpertUiState(runtime, bundle), remember { SnackbarHostState() }, {}, commands::add,
                    { starts++ }, {}, {}, false
                )
            }
        }
        rule.onNodeWithText("Поднимаем TUN").assertIsDisplayed()
        capture("android-expert-overview.png")
        rule.onNodeWithText("Остановить TUN").performClick()
        rule.runOnIdle {
            assertThat(starts).isEqualTo(0)
            assertThat(commands).containsExactly(ExpertIntent.Stop)
        }
    }

    @Test
    fun ruleCanBeCommittedWithVisibleCheckWithoutKeyboardDone() {
        var committed: PolicyNode? = null
        val node = PolicyNode("new-rule")
        rule.setContent {
            LerNetTheme {
                ExpertRuleEditor(node, app.lernet.routing.policy.PolicyScope.Device, bundle, NetworkPolicy(), { committed = it }, {})
            }
        }
        rule.onNodeWithText("✓ Готово").assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(committed?.id).isEqualTo("new-rule") }
        capture("android-expert-rule-editor.png", dialog = true)
    }

    @Test
    fun longPressDragCommitsOnlyTheFingerDeltaAndRetainsSemanticRule() {
        val node = PolicyNode("drag", title = "Drag rule")
        val tree = PolicyTree(
            PolicyScope.Device, listOf(node),
            positions = mapOf(
                PolicyCanvasKeys.node(node.id) to PolicyCanvasPoint(100f, 180f)
            )
        )
        var committed: Pair<String, PolicyCanvasPoint>? = null
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            LerNetTheme {
                ExpertGraph(
                    tree, bundle, NetworkPolicy(device = tree), emptySet(), {}, {}, {},
                    { key, point -> committed = key to point }, {}
                )
            }
        }
        rule.onNodeWithText("Drag rule").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(60f, 30f))
            moveBy(Offset(60f, 30f))
            up()
        }
        rule.runOnIdle {
            assertThat(committed?.first).isEqualTo(PolicyCanvasKeys.node(node.id))
            assertThat(requireNotNull(committed).second.x).isWithin(0.1f).of(100f + 120f / density)
            assertThat(requireNotNull(committed).second.y).isWithin(0.1f).of(180f + 60f / density)
            assertThat(tree.nodes).containsExactly(node)
        }
        capture("android-expert-graph.png")
    }

    @Test
    fun externalProxyCanBeSavedFromVisibleButtonWithoutKeyboardDone() {
        var committed: ExternalExitRequest? = null
        var dismissed = false
        rule.setContent {
            LerNetTheme {
                ExpertExternalExitEditor(null, { dismissed = true }) { request, existing ->
                    assertThat(existing).isNull()
                    committed = request
                    null
                }
            }
        }
        rule.onNodeWithText("Название выхода").performTextInput("Local corporate relay")
        rule.onNodeWithText("Сервер или IP без схемы и порта").performTextInput("127.0.0.1")
        rule.onNodeWithText("✓ Готово").assertIsDisplayed()
        capture("android-expert-external-editor.png", dialog = true)
        rule.onNodeWithText("✓ Готово").performClick()
        rule.runOnIdle {
            assertThat(dismissed).isTrue()
            assertThat(committed?.host).isEqualTo("127.0.0.1")
            assertThat(ExternalExitProfiles.validate(requireNotNull(committed))).isEmpty()
        }
    }

    @Test
    fun failedExpertOffersExplicitStopToClearDesiredSessionBeforeRecovery() {
        val commands = mutableListOf<ExpertIntent>()
        var starts = 0
        rule.setContent {
            LerNetTheme {
                ExpertScreen(
                    ExpertUiState(ExpertRuntimeState(saved = NetworkPolicy(), phase = ExpertSessionPhase.FAILED), bundle),
                    remember { SnackbarHostState() }, {}, commands::add, { starts++ }, {}, {}, false
                )
            }
        }
        rule.onNodeWithText("Остановить TUN").performClick()
        rule.runOnIdle {
            assertThat(commands).containsExactly(ExpertIntent.Stop)
            assertThat(starts).isEqualTo(0)
        }
    }

    @Test
    fun corporateProfileWithAdvancedDnsRemainsVisibleAndReadOnly() {
        val profile = ExternalExitProfiles.build(
            ExternalExitRequest(
                ExternalExitKind.CORPORATE_INTERFACE, "Corporate interface",
                binding = VerifiedInterfaceBinding("d2267390-8548-4d79-9e3f-f1a1c593fb9a", "Fixture adapter", 17),
            )
        ).copy(dnsJson = "{}")
        assertThat(ExternalExitProfiles.describe(profile)).isNull()
        rule.setContent {
            LerNetTheme { ExpertExternalExitDetails(profile, {}, {}) }
        }
        rule.onNodeWithText("Fixture adapter").assertIsDisplayed()
        rule.onNodeWithText("Изменить внешний прокси").assertDoesNotExist()
        capture("android-expert-corporate-unavailable.png", dialog = true)
    }

    @Test
    fun existingExternalProxyEditorKeepsItsTransportKind() {
        val profile = ExternalExitProfiles.build(ExternalExitRequest(ExternalExitKind.SOCKS5, "Fixture proxy", "127.0.0.1", 1080))
        rule.setContent {
            LerNetTheme { ExpertExternalExitEditor(profile, {}) { _, _ -> null } }
        }
        rule.onNodeWithText("SOCKS5").assertIsDisplayed()
        rule.onNodeWithText("HTTP-прокси").assertDoesNotExist()
        rule.onNodeWithText("✓ Готово").assertIsDisplayed()
        capture("android-expert-external-edit.png", dialog = true)
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val file = File("build/reports/ui/$name")
        file.parentFile?.mkdirs()
        rule.runOnIdle {
            val activityView = rule.activity.window.decorView
            val windows = WindowInspector.getGlobalWindowViews().filter {
                it.visibility == View.VISIBLE &&
                    it.width in 1..8192 &&
                    it.height in 1..8192 &&
                    it.width.toLong() * it.height <= 32_000_000
            }
            val view = if (dialog) {
                requireNotNull(windows.lastOrNull { it !== activityView }) {
                    "Dialog capture must draw its actual visible window"
                }
            } else {
                activityView
            }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
