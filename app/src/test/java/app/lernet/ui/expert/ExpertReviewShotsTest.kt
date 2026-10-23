package app.lernet.ui.expert

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lernet.config.model.Group
import app.lernet.config.model.Profile
import app.lernet.config.model.ProfileSource
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.policy.ExpertExitKey
import app.lernet.engine.policy.ExpertExitState
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.TunIdentity
import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.ui.home.ConfigDrawer
import app.lernet.ui.home.DrawerActions
import app.lernet.ui.home.ProfileProbe
import app.lernet.ui.theme.LerNetTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review shots for owner: Expert Пульт / Конфиги / События + drawer «Анализ сети».
 * Writes straight to out/android-expert-ui/ (local only; not asserted pixel-wise).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpertReviewShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("/workspace/projects/LerNET-ui3/out/android-expert-ui").also { it.mkdirs() }

    private fun profile(id: String, name: String) = TransferProfile(
        id, name, "VLESS",
        listOf(TransferOutbound("main", "proxy", "vless", """{"type":"vless","server":"ex.invalid","server_port":443}""")),
        "main",
    )

    private val work = TransferGroup("work", "Работа", listOf("nl", "de"), autoSwap = true)
    private val bundle = TransferBundle(
        scope = "all",
        groups = listOf(work),
        profiles = listOf(profile("nl", "Нидерланды"), profile("de", "Германия"), profile("fi", "Финляндия")),
        rules = emptyList(),
    )
    private val channel = PolicyChannel("ch1", "Офис", PolicyScope.Device, PolicyTarget.Profile("nl"))
    private val policy = NetworkPolicy(
        channels = listOf(channel),
        folderPolicies = listOf(
            FolderPolicy(folderId = "work", selection = FolderSelection.PREFERRED, preferredProfileId = "nl", autoSwap = true),
        ),
    )
    private val exits = listOf(
        ExpertExitState(ExpertExitKey("nl"), ExitPhase.READY, latencyMs = 38, activeFlows = 12, pendingFlows = 0),
        ExpertExitState(ExpertExitKey("de"), ExitPhase.SLEEPING, latencyMs = 51, activeFlows = 0),
        ExpertExitState(ExpertExitKey("nl", channelId = "ch1"), ExitPhase.READY, latencyMs = 42, activeFlows = 3),
    )
    private val runtime = ExpertRuntimeState(
        saved = policy, draft = policy,
        phase = ExpertSessionPhase.RUNNING, desiredEnabled = true,
        runningSinceEpochMs = System.currentTimeMillis() - ((14 * 24 + 6) * 60 + 5) * 60_000L,
        tun = TunIdentity("tun"), appliedRevision = 1, appliedPolicy = policy,
        exits = exits, selectedFolderProfiles = mapOf("work" to "nl"),
    )

    private fun capture(name: String) {
        val file = File(outDir, name)
        rule.waitForIdle()
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun expert(section: ExpertSection) {
        rule.setContent {
            LerNetTheme {
                ExpertScreen(
                    ExpertUiState(runtime, bundle),
                    remember { SnackbarHostState() },
                    {}, {}, {}, {}, {}, false,
                    // Stub so advanced SOCKS/HTTP stays a TextButton, not a primary CTA.
                    onSaveExternal = { _, _ -> null },
                    selectedSection = section, onSectionChange = {},
                    onSettings = {}, onOpenDiag = {},
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xxhdpi")
    fun pult() {
        expert(ExpertSection.OVERVIEW)
        rule.onNodeWithTag("expert_diag").assertIsDisplayed()
        rule.onNodeWithTag("expert_settings").assertIsDisplayed()
        rule.onNodeWithTag("expert_pult_settings").assertIsDisplayed()
        capture("01-pult.png")
    }

    @Test
    @Config(qualifiers = "w390dp-h1600dp-xxhdpi")
    fun configs() {
        expert(ExpertSection.EXITS)
        rule.onNodeWithTag("expert_exits_segments").assertIsDisplayed()
        rule.onNodeWithTag("expert_exits_configs").assertIsDisplayed()
        rule.onNodeWithTag("expert_folder_work").assertIsDisplayed()
        rule.onNodeWithTag("expert_folder_header_work").assertIsDisplayed()
        rule.onNodeWithTag("expert_config_row_nl").assertIsDisplayed()
        rule.onNodeWithTag("expert_config_row_de").assertIsDisplayed()
        rule.onNodeWithTag("expert_folder_ungrouped").assertIsDisplayed()
        capture("02-configs.png")
    }

    @Test
    @Config(qualifiers = "w390dp-h1200dp-xxhdpi")
    fun configsChannels() {
        expert(ExpertSection.EXITS)
        rule.onNodeWithTag("expert_exits_segments").assertIsDisplayed()
        // Slim segment «Каналы» (second option); avoid matching other headings.
        rule.onNodeWithText("Каналы").performClick()
        rule.onNodeWithTag("expert_exits_channels").assertIsDisplayed()
        rule.onNodeWithTag("expert_channel_row_ch1").assertIsDisplayed()
        rule.onNodeWithTag("expert_channel_add").assertIsDisplayed()
        capture("02-configs-channels.png")
    }

    @Test
    fun drawer() {
        rule.mainClock.autoAdvance = false
        val noop1: (String) -> Unit = {}
        val actions = DrawerActions(
            noop1, {}, noop1, {}, {}, noop1, noop1, noop1, noop1, noop1, noop1, noop1,
            { _, _ -> }, {}, { _, _, _ -> }, { _, _ -> }, noop1, { _, _ -> }, { _, _ -> }, { _, _ -> },
        )
        val profiles = listOf(
            Profile("nl", "Нидерланды", 0, 0, ProfileSource.VLESS, "main", emptyList(), null, null),
            Profile("de", "Германия", 0, 0, ProfileSource.VLESS, "main", emptyList(), null, null),
            Profile("fi", "Финляндия", 0, 0, ProfileSource.VLESS, "main", emptyList(), null, null),
        )
        val groups = listOf(Group("work", "Работа", listOf("nl", "de"), autoFailover = true))
        rule.setContent {
            LerNetTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box {
                        ModalDrawerSheet {
                            ConfigDrawer(
                                profiles = profiles, groups = groups, activeProfileId = "nl",
                                probes = mapOf(
                                    "nl" to ProfileProbe(tcpMs = 38, reachable = true, tunnelMs = 142, tunnelChecked = true),
                                    "de" to ProfileProbe(tcpMs = 51, reachable = true),
                                    "fi" to ProfileProbe(tcpMs = null, reachable = false),
                                ),
                                actions = actions,
                                onNetworkAnalysis = {},
                            )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithTag("drawer_network_analysis").assertIsDisplayed()
        capture("03-drawer.png")
    }

    @Test
    fun events() {
        expert(ExpertSection.JOURNAL)
        capture("04-events.png")
    }
}
