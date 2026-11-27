package app.lernet.ui.routes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lernet.R
import app.lernet.config.db.LerNetDatabase
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.transfer.*
import app.lernet.engine.ConnectionController
import app.lernet.engine.UnavailableBoxEngine
import app.lernet.ui.expert.AppModeTabs
import app.lernet.ui.theme.LerNetTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class RouteEditorWorkspaceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun folderRulesOpenTheSharedMapAndSaveTheOriginalVpnRecord() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LerNetDatabase::class.java).build()
        val repository = ConfigRepository(db.profileDao(), db.outboundDao(), db.ruleNodeDao(), db.groupDao(), db.groupMemberDao(), database = db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ConnectionController(UnavailableBoxEngine(), scope)
        val owner = "grp_work"
        val main = TransferOutbound("office-exit", "proxy", "socks", """{"type":"socks","server":"office.example","server_port":1080}""")
        val office = TransferProfile("office", "Офис", "JSON_PASTE", listOf(main), main.id)
        val home = office.copy(id = "home", name = "Дом", outbounds = listOf(main.copy(id = "home-exit")), selectedOutboundId = "home-exit")
        val rules = listOf(
            TransferRule("work", owner, sortIndex = 0, action = "proxy", title = "Рабочие сервисы", domainSuffixes = listOf("office.example")),
            TransferRule("sso", owner, "work", sortIndex = 0, action = "proxy", title = "Единый вход",
                domains = listOf("login.office.example"), pipeName = "Офис"),
            TransferRule("work-else", owner, "work", sortIndex = 1, action = "direct"),
            TransferRule("local", owner, sortIndex = 1, action = "direct", title = "Локальная сеть", ipCidrs = listOf("10.0.0.0/8")),
            TransferRule("else", owner, sortIndex = 2, action = "proxy"),
            TransferRule("another", home.id, sortIndex = 0, action = "proxy", title = "Единый вход"),
        )
        try {
            runBlocking {
                repository.restoreTransferExact(TransferBundle(scope = "all", profiles = listOf(office, home),
                    groups = listOf(TransferGroup("work", "Работа", listOf(office.id), false)), rules = rules))
            }
            val before = runBlocking { repository.listRuleNodes(owner) }
            val untouched = runBlocking { repository.listRuleNodes(home.id) }
            fun load() = RouteEditorViewModel(SavedStateHandle(mapOf("profileId" to owner)), repository, controller)
            var vm by mutableStateOf(load())
            rule.setContent {
                val state by vm.state.collectAsState()
                LerNetTheme { RouteEditorScreen(state, vm::onIntent, {}) }
            }
            awaitDatabase { !vm.state.value.loading }
            rule.onNodeWithTag("branch-row:work").assertIsDisplayed()
            capture("vpn-branch-list", R.string.back)
            rule.onNodeWithTag("branch-row:work").performClick()
            rule.onNodeWithTag("branch-row:sso").assertIsDisplayed()
            rule.onNodeWithTag("branch-map-open").performClick()
            rule.onNodeWithTag("branch-map-node:sso").performClick()
            rule.onNodeWithTag("branch-map-zoom-in").performClick()
            rule.onNodeWithTag("branch-map-fit").performClick()
            capture("vpn-branch-map", R.string.routes_close_editor)
            rule.onNodeWithTag("branch-map-edit").performClick()
            rule.onNode(hasText("Единый вход") and hasSetTextAction()).performTextReplacement("Вход в портал")
            rule.onNodeWithText(rule.activity.getString(R.string.done)).performClick()
            rule.onNodeWithTag("branch-list-back").performClick()
            rule.onNodeWithTag("branch-row:sso").assertTextContains("Вход в портал")
            assertThat(runBlocking { repository.listRuleNodes(owner) }).isEqualTo(before)
            // A failed layout write must roll back the preceding replacement of the rules.
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_layout BEFORE INSERT ON groups WHEN NEW.id = 'work' BEGIN SELECT RAISE(ABORT, 'layout failure'); END",
            )
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.save)).performClick()
            awaitDatabase { vm.state.value.fieldErrors.isNotEmpty() && !vm.state.value.saving }
            assertThat(vm.state.value.saved).isFalse()
            assertThat(runBlocking { repository.listRuleNodes(owner) }).isEqualTo(before)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_layout")
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.save)).performClick()
            awaitDatabase { vm.state.value.saved && !vm.state.value.saving }
            val saved = runBlocking { repository.listRuleNodes(owner) }
            assertThat(saved).containsExactlyElementsIn(before.map { if (it.id == "sso") it.copy(title = "Вход в портал") else it })
            assertThat(runBlocking { repository.listRuleNodes(home.id) }).isEqualTo(untouched)
            rule.runOnIdle { vm = load() }
            awaitDatabase { !vm.state.value.loading }
            rule.onNodeWithTag("branch-search").performClick()
            rule.onNodeWithTag("branch-search-field").performTextInput("Вход в портал")
            rule.onNodeWithTag("branch-search-result:sso").assertTextContains("Рабочие сервисы", substring = true)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    private fun awaitDatabase(ready: () -> Boolean) {
        rule.waitUntil(15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            ready()
        }
    }

    private fun capture(name: String, navigationDescription: Int) {
        rule.waitForIdle()
        val navigation = rule.onNodeWithContentDescription(rule.activity.getString(navigationDescription))
            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val file = File("build/reports/ui/android-$name.png")
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val xRange = navigation.left.toInt() until navigation.right.toInt()
                val yRange = navigation.top.toInt() until navigation.bottom.toInt()
                val background = bitmap.getPixel(xRange.first, yRange.first)
                assertThat(yRange.any { y -> xRange.any { x -> bitmap.getPixel(x, y) != background } }).isTrue()
            } finally { bitmap.recycle() }
        }
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
