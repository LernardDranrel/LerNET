package app.lernet.ui.routes

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.ui.expert.AppModeTabs
import app.lernet.ui.theme.LerNetTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the shared UI shell without constructing a VPN service or network client. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w320dp-h640dp-xhdpi")
class RouteEditorWorkspaceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectingTheCurrentViewDoesNotDiscardOrResaveTheGraph() {
        val changes = mutableListOf<Boolean>()
        rule.setContent {
            var asList by remember { mutableStateOf(false) }
            LerNetTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    RouteEditorWorkspace(
                        asList = asList,
                        onListChange = {
                            changes += it
                            asList = it
                        },
                        bottomBar = { Text("Durable save fixture") },
                    ) { Text(if (asList) "List fixture" else "Graph fixture") }
                }
            }
        }
        val schema = rule.activity.getString(R.string.route_schema)
        val list = rule.activity.getString(R.string.route_list)
        rule.onNodeWithText(schema).assertIsSelected().performClick()
        rule.runOnIdle { assertThat(changes).isEmpty() }
        rule.onNodeWithText(list).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(list).assertIsSelected()
        rule.onNodeWithText("List fixture").assertIsDisplayed()
        rule.onNodeWithText("Durable save fixture").assertIsDisplayed()
        rule.onNodeWithText(schema).performClick()
        rule.runOnIdle { assertThat(changes).containsExactly(true, false).inOrder() }
    }

    @Test
    fun modeTabsNavigateOnlyAfterAnExplicitTap() {
        var vpnVisits = 0
        var expertVisits = 0
        rule.setContent {
            var expert by remember { mutableStateOf(false) }
            LerNetTheme {
                AppModeTabs(
                    expert,
                    onVpn = {
                        vpnVisits++
                        expert = false
                    },
                    onExpert = {
                        expertVisits++
                        expert = true
                    },
                )
            }
        }
        rule.runOnIdle {
            assertThat(vpnVisits).isEqualTo(0)
            assertThat(expertVisits).isEqualTo(0)
        }
        rule.onNodeWithText(rule.activity.getString(R.string.expert_tab_vpn)).assertIsSelected()
        rule.onNodeWithText(rule.activity.getString(R.string.expert_tab_expert)).assertIsDisplayed().performClick().assertIsSelected()
        rule.onNodeWithText(rule.activity.getString(R.string.expert_tab_expert)).assertIsSelected()
        rule.runOnIdle {
            assertThat(vpnVisits).isEqualTo(0)
            assertThat(expertVisits).isEqualTo(1)
        }
    }
}
