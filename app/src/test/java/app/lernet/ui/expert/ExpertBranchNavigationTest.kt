package app.lernet.ui.expert

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import app.lernet.R
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.policy.ExpertIntent
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.RuleConditions
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpertBranchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val intents = mutableListOf<ExpertIntent>()
    private val office = TransferProfile("office", "Москва · офис", "VLESS",
        listOf(TransferOutbound("main", "proxy", "vless", """{"type":"vless","server":"office.example","server_port":443}""")), "main")
    private val bundle = TransferBundle(scope = "all", groups = emptyList(), profiles = listOf(office), rules = emptyList())

    private fun node(id: String, title: String, parent: String? = null, order: Int = 0, domain: String = "*.corp.example") =
        PolicyNode(id, parentId = parent, sortIndex = order, title = title,
            conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf(domain)))))

    private fun openSchema(policy: NetworkPolicy, save: (NetworkPolicy, NetworkPolicy) -> Unit = { _, _ -> error("Unexpected write") }) {
        val runtime = mutableStateOf(ExpertRuntimeState(saved = policy))
        compose.setContent {
            LerNetTheme {
                ExpertScreen(
                    ExpertUiState(runtime.value, bundle), remember { SnackbarHostState() },
                    {}, intents::add, {}, {}, {}, false,
                    onSaveDraft = { base, next ->
                        save(base, next)
                        runtime.value = runtime.value.copy(draft = next)
                        null
                    },
                )
            }
        }
        compose.onNodeWithTag("expert_tab_schema").performClick()
    }

    @Test
    fun selectZoomFitEditAndReturnKeepsTheSameBranchWithoutPublishingTheDraft() {
        val tree = PolicyTree(
            PolicyScope.Device,
            listOf(
                node("work", "Работа"), node("access", "Доступ сотрудников", "work", domain = "*.portal.corp.example"),
                node("sso", "Единый вход", "access", domain = "login.portal.corp.example")
                    .copy(target = PolicyTarget.Profile("office")),
                node("cert", "Проверка сертификатов сотрудников и подрядчиков", "access", 1, "ca.portal.corp.example")
                    .copy(target = PolicyTarget.Profile("office")),
                PolicyNode("else", parentId = "access", sortIndex = 2, otherwise = true, target = PolicyTarget.Block),
            ),
            positions = mapOf("sso" to PolicyCanvasPoint(650f, -240f)),
        )
        val policy = NetworkPolicy(device = tree)
        val changes = mutableListOf<NetworkPolicy>()
        openSchema(policy) { base, next ->
            assertThat(base).isEqualTo(policy)
            changes += next
        }
        compose.onNodeWithTag("branch-row:work").assertIsDisplayed().performClick()
        compose.onNodeWithTag("branch-row:access").assertIsDisplayed().performClick()
        compose.onNodeWithTag("branch-row:sso").assertIsDisplayed()
        compose.onNodeWithTag("branch-row:work").assertDoesNotExist()
        capture("branch-list")

        compose.onNodeWithTag("branch-map-open").performClick()
        compose.onNodeWithTag("branch-map-node:access").assertIsDisplayed()
        val leaf = compose.onNodeWithTag("branch-map-node:sso")
        leaf.assertIsDisplayed().performClick()
        val small = leaf.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("branch-map-zoom-in").performClick()
        assertThat(leaf.fetchSemanticsNode().boundsInRoot.width).isGreaterThan(small.width)
        compose.onNodeWithTag("branch-map-fit").performClick()
        assertThat(leaf.fetchSemanticsNode().boundsInRoot.width).isWithin(2f).of(small.width)
        compose.onNodeWithTag("expert-branch-map").performTouchInput {
            val spacing = width * 0.08f
            down(0, center - Offset(spacing, 0f))
            down(1, center + Offset(spacing, 0f))
            for (step in 1..6) {
                val spread = Offset(spacing * (1f + step * 0.12f), 0f)
                moveTo(0, center - spread)
                moveTo(1, center + spread)
            }
            up(1)
            up(0)
        }
        assertThat(leaf.fetchSemanticsNode().boundsInRoot.width).isGreaterThan(small.width + 2f)
        compose.onNodeWithTag("branch-map-fit").performClick()
        compose.onNodeWithTag("branch-map-node:else").assertIsDisplayed()
        compose.runOnIdle {
            assertThat(changes).isEmpty()
            assertThat(intents).isEmpty()
        }
        capture("branch-map")

        compose.onNodeWithTag("branch-map-edit").assertIsDisplayed().performClick()
        val name = compose.onNodeWithText(compose.activity.getString(R.string.expert_rule_title))
        name.assertIsDisplayed()
        capture("branch-editor", dialog = true)
        name.performTextReplacement("Вход в портал")
        compose.onNodeWithTag("expert_rule_save_draft").performClick()
        leaf.assertIsDisplayed().assertTextContains("Вход в портал")
        compose.onNodeWithTag("branch-list-back").performClick()
        compose.onNodeWithTag("branch-row:sso").assertIsDisplayed().assertTextContains("Вход в портал")
        compose.runOnIdle {
            val changedTree = changes.single().device
            assertThat(changedTree.nodes.single { it.id == "sso" }.parentId).isEqualTo("access")
            assertThat(changedTree.nodes.single { it.id == "cert" }).isEqualTo(tree.nodes.single { it.id == "cert" })
            assertThat(changedTree.positions).isEqualTo(tree.positions)
            assertThat(intents).isEmpty()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun globalSearchDistinguishesSameTitlesAndPathJumpsAcrossSixtyLevels() {
        val chain = (1..59).map { level ->
            node("level-$level", if (level == 59) "Доступ сотрудников" else "Уровень $level",
                if (level == 1) null else "level-${level - 1}")
        }
        val tree = PolicyTree(PolicyScope.Device, chain + listOf(
            node("employee", "Единый вход", "level-59"),
            node("partners", "Партнёры", order = 1),
            node("partner", "Единый вход", "partners"),
        ))
        openSchema(NetworkPolicy(device = tree))
        compose.onNodeWithTag("branch-search").performClick()
        val search = compose.onNodeWithTag("branch-search-field")
        search.performTextInput("Единый вход")
        compose.onNodeWithTag("branch-search-result:employee").assertTextContains("Доступ сотрудников", substring = true)
        compose.onNodeWithTag("branch-search-result:partner").assertTextContains("Партнёры", substring = true)
        capture("branch-search", dialog = true)
        search.performTextReplacement("Доступ сотрудников")
        compose.onNodeWithTag("branch-search-result:level-59").performClick()
        compose.onNodeWithTag("branch-row:employee").assertIsDisplayed()
        compose.onNodeWithTag("branch-path").performClick()
        compose.onNodeWithTag("branch-ancestor:level-59").assertIsDisplayed()
        capture("branch-deep-path", dialog = true)
        compose.onNodeWithTag("branch-ancestors").performScrollToNode(hasTestTag("branch-ancestor:level-28"))
        compose.onNodeWithTag("branch-ancestor:level-28").performClick()
        compose.onNodeWithTag("branch-row:level-29").assertIsDisplayed()
        compose.runOnIdle { assertThat(intents).isEmpty() }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val file = File("build/reports/ui/android-$name.png")
        file.parentFile?.mkdirs()
        compose.runOnIdle {
            val activity = compose.activity.window.decorView
            val view = if (dialog) WindowInspector.getGlobalWindowViews().last {
                it !== activity && it.visibility == View.VISIBLE && it.width > 0 && it.height > 0
            } else activity
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
