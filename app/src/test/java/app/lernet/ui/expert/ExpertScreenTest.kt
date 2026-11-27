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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.policy.PolicyTemplateChoice
import app.lernet.config.policy.VerifiedInterfaceBinding
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExpertIntent
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.engine.policy.PolicyControlCapabilities
import app.lernet.ui.controls.ExpertTemplatePicker
import app.lernet.ui.controls.TemplateAction
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
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun templateSwitchKeepsRunningPolicyUntilApply() {
        val general = NetworkPolicy(revision = 4)
        val work = general.copy(revision = 5)
        val commands = mutableListOf<ExpertIntent>()
        val snapshot = ExpertRuntimeState(
            saved = general, phase = ExpertSessionPhase.RUNNING, desiredEnabled = true,
            appliedPolicy = general, appliedRevision = general.revision,
            capabilities = PolicyControlCapabilities(true, true, true),
        )
        val ui = androidx.compose.runtime.mutableStateOf(ExpertUiState(
            snapshot, bundle, templates = listOf(PolicyTemplateChoice("general", "Общий"), PolicyTemplateChoice("work", "Рабочий")),
            appliedTemplateId = "general",
        ))
        rule.setContent {
            FixtureTheme {
                ExpertScreen(ui.value, remember { SnackbarHostState() }, {}, commands::add, {}, {}, {}, false,
                    onTemplateChange = { action ->
                        assertThat(action).isEqualTo(TemplateAction.Select("work"))
                        ui.value = ui.value.copy(selectedTemplateId = "work", runtime = snapshot.copy(saved = work, draft = work))
                        null
                    })
            }
        }
        rule.onNodeWithTag("expert_template_picker").performClick()
        rule.onNodeWithText("Рабочий").performClick()
        rule.onNodeWithText("Сейчас работает «Общий»").assertIsDisplayed()
        rule.runOnIdle { assertThat(commands).isEmpty() }
        capture("android-expert-template-switch.png")
        rule.onNodeWithText("Применить шаблон").performClick()
        rule.runOnIdle { assertThat(commands).containsExactly(ExpertIntent.ApplySaved) }
    }

    @Test
    fun templateCopyRetainsTheEnteredNameWhenSavingFails() {
        rule.setContent {
            FixtureTheme {
                ExpertTemplatePicker(listOf("general" to "Общий"), "general", enabled = true,
                    onChange = {
                        assertThat(it).isEqualTo(TemplateAction.Create("Рабочий"))
                        "Ошибка сохранения"
                    })
            }
        }
        rule.onNodeWithTag("expert_template_picker").performClick()
        rule.onNodeWithText("Создать копию…").performClick()
        rule.onNodeWithTag("expert_template_name").performTextInput("Рабочий")
        rule.onNodeWithText("Создать").performClick()
        rule.onNodeWithText("Ошибка сохранения").assertIsDisplayed()
        rule.onNodeWithText("Рабочий").assertIsDisplayed()
    }

    @Test
    fun compactSchemaKeepsItsRootVisibleInSmallRemainingViewport() {
        rule.setContent {
            FixtureTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    ExpertSchema(ExpertRuntimeState(saved = NetworkPolicy()), bundle, {}, Modifier.height(360.dp))
                }
            }
        }
        rule.onNodeWithTag("branch-map-open").assertIsDisplayed()
        rule.onNodeWithTag("branch-search").assertIsDisplayed()
        capture("android-expert-schema-compact.png")
    }

    @Test
    fun healthSettingsStayOpenWhenDurableWriteIsRejected() {
        var dismissed = false
        var requested: PolicyHealthSettings? = null
        rule.setContent {
            FixtureTheme {
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
            FixtureTheme {
                ExpertScreen(
                    ExpertUiState(runtime, bundle), remember { SnackbarHostState() }, {}, commands::add,
                    { starts++ }, {}, {}, false
                )
            }
        }
        // v2 texts: plain words instead of "TUN".
        rule.onAllNodesWithText("Включаем…").onFirst().assertExists()
        capture("android-expert-overview.png")
        rule.onNodeWithText("Отменить запуск").performScrollTo().performClick()
        rule.runOnIdle {
            assertThat(starts).isEqualTo(0)
            assertThat(commands).containsExactly(ExpertIntent.Stop)
        }
    }

    @Test
    fun ruleCanBeCommittedWithVisibleCheckWithoutKeyboardDone() {
        var committed: PolicyNode? = null
        // Since the «ИНАЧЕ» model a new rule needs a condition (an empty one is the otherwise branch).
        val node = PolicyNode("new-rule", conditions = app.lernet.routing.RuleConditions(
            blocks = listOf(app.lernet.routing.ConditionBlock(app.lernet.routing.ConditionKind.DOMAIN, listOf("example.com")))))
        rule.setContent {
            FixtureTheme {
                ExpertRuleEditor(node, app.lernet.routing.policy.PolicyScope.Device, bundle, NetworkPolicy(), { committed = it; null }, {})
            }
        }
        rule.onNodeWithTag("expert_rule_save_draft").assertIsDisplayed().performClick()
        rule.runOnIdle { assertThat(committed?.id).isEqualTo("new-rule") }
        capture("android-expert-rule-editor.png", dialog = true)
    }

    @Test
    fun longPressDragCommitsOnlyTheFingerDeltaAndRetainsSemanticRule() {
        // A last rule without conditions is shown as the legacy «ИНАЧЕ» branch, so the fixture needs a condition.
        val node = PolicyNode("drag", title = "Drag rule", conditions = app.lernet.routing.RuleConditions(
            blocks = listOf(app.lernet.routing.ConditionBlock(app.lernet.routing.ConditionKind.DOMAIN, listOf("example.com")))))
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
            FixtureTheme {
                ExpertGraph(
                    tree, bundle, NetworkPolicy(device = tree), emptySet(), {}, {}, {},
                    { key, point -> committed = key to point }, {}
                )
            }
        }
        // The hand (pan) mode is on by default; a block moves only after it is switched off.
        rule.onNodeWithContentDescription("Перемещение полотна").performClick()
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
            FixtureTheme {
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
            FixtureTheme {
                ExpertScreen(
                    ExpertUiState(ExpertRuntimeState(saved = NetworkPolicy(), phase = ExpertSessionPhase.FAILED), bundle),
                    remember { SnackbarHostState() }, {}, commands::add, { starts++ }, {}, {}, false
                )
            }
        }
        rule.onNodeWithText("Остановить").performScrollTo().performClick()
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
            FixtureTheme { ExpertExternalExitDetails(profile, {}, {}) }
        }
        rule.onNodeWithText("Fixture adapter").assertIsDisplayed()
        rule.onNodeWithText("Изменить внешний выход").assertDoesNotExist()
        capture("android-expert-corporate-unavailable.png", dialog = true)
    }

    @Test
    fun existingExternalProxyEditorKeepsItsTransportKind() {
        val profile = ExternalExitProfiles.build(ExternalExitRequest(ExternalExitKind.SOCKS5, "Fixture proxy", "127.0.0.1", 1080))
        rule.setContent {
            FixtureTheme { ExpertExternalExitEditor(profile, {}) { _, _ -> null } }
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

@androidx.compose.runtime.Composable
private fun FixtureTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    LerNetTheme {
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
        ) { content() }
    }
}
