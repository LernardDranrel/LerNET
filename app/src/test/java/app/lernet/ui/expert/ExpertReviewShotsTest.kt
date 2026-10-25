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
import org.robolectric.shadows.ShadowDialog

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

    private val subUrl = "https://example.com/sub"

    private fun profile(id: String, name: String, subscriptionUrl: String? = null) = TransferProfile(
        id, name, "VLESS",
        listOf(TransferOutbound("main", "proxy", "vless", """{"type":"vless","server":"ex.invalid","server_port":443}""")),
        "main",
        subscriptionUrl = subscriptionUrl,
    )

    private val work = TransferGroup("work", "Работа", listOf("nl", "de"), autoSwap = true)
    private val subscription = TransferGroup(
        "sub", "Подписка · example.com", listOf("sg", "jp", "us"),
        autoSwap = true, subscriptionUrl = subUrl,
    )
    private val bundle = TransferBundle(
        scope = "all",
        groups = listOf(work),
        profiles = listOf(profile("nl", "Нидерланды"), profile("de", "Германия"), profile("fi", "Финляндия")),
        rules = emptyList(),
    )
    private val subscriptionBundle = TransferBundle(
        scope = "all",
        groups = listOf(work, subscription),
        profiles = listOf(
            profile("nl", "Нидерланды"),
            profile("de", "Германия"),
            profile("fi", "Финляндия"),
            profile("sg", "Сингапур", subUrl),
            profile("jp", "Токио", subUrl),
            profile("us", "США", subUrl),
        ),
        rules = emptyList(),
    )
    private val channel = PolicyChannel("ch1", "Офис", PolicyScope.Device, PolicyTarget.Profile("nl"))
    private val policy = NetworkPolicy(
        channels = listOf(channel),
        folderPolicies = listOf(
            FolderPolicy(folderId = "work", selection = FolderSelection.PREFERRED, preferredProfileId = "nl", autoSwap = true),
        ),
    )
    private val subscriptionPolicy = NetworkPolicy(
        channels = listOf(channel),
        folderPolicies = listOf(
            FolderPolicy(folderId = "work", selection = FolderSelection.PREFERRED, preferredProfileId = "nl", autoSwap = true),
            FolderPolicy(folderId = "sub", selection = FolderSelection.STABLE, preferredProfileId = "sg", autoSwap = true),
        ),
    )
    private val exits = listOf(
        ExpertExitState(ExpertExitKey("nl"), ExitPhase.READY, latencyMs = 38, activeFlows = 12, pendingFlows = 0),
        ExpertExitState(ExpertExitKey("de"), ExitPhase.SLEEPING, latencyMs = 51, activeFlows = 0),
        ExpertExitState(ExpertExitKey("nl", channelId = "ch1"), ExitPhase.READY, latencyMs = 42, activeFlows = 3),
    )
    private val subscriptionExits = exits + listOf(
        ExpertExitState(ExpertExitKey("sg"), ExitPhase.READY, latencyMs = 64, activeFlows = 4, pendingFlows = 0),
        ExpertExitState(ExpertExitKey("jp"), ExitPhase.READY, latencyMs = 91, activeFlows = 1),
        ExpertExitState(ExpertExitKey("us"), ExitPhase.SLEEPING, latencyMs = 120, activeFlows = 0),
    )
    private val runtime = ExpertRuntimeState(
        saved = policy, draft = policy,
        phase = ExpertSessionPhase.RUNNING, desiredEnabled = true,
        runningSinceEpochMs = System.currentTimeMillis() - ((14 * 24 + 6) * 60 + 5) * 60_000L,
        tun = TunIdentity("tun"), appliedRevision = 1, appliedPolicy = policy,
        exits = exits, selectedFolderProfiles = mapOf("work" to "nl"),
    )
    private val subscriptionRuntime = ExpertRuntimeState(
        saved = subscriptionPolicy, draft = subscriptionPolicy,
        phase = ExpertSessionPhase.RUNNING, desiredEnabled = true,
        runningSinceEpochMs = System.currentTimeMillis() - ((14 * 24 + 6) * 60 + 5) * 60_000L,
        tun = TunIdentity("tun"), appliedRevision = 1, appliedPolicy = subscriptionPolicy,
        exits = subscriptionExits,
        selectedFolderProfiles = mapOf("work" to "nl", "sub" to "sg"),
    )

    private fun capture(name: String, waitIdle: Boolean = true) {
        val file = File(outDir, name)
        if (waitIdle) rule.waitForIdle()
        rule.runOnUiThread {
            // AlertDialog lives in its own window; prefer it when open so review shots show menus.
            val view = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
                ?: rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun expert(
        section: ExpertSection,
        ui: ExpertUiState = ExpertUiState(runtime, bundle),
        onCopyProfile: (suspend (String) -> Unit)? = { },
        onRenameFolder: (suspend (String, String) -> Unit)? = { _, _ -> },
        onDeleteFolder: (suspend (String) -> Unit)? = { },
        onRefreshSubscription: (suspend (String) -> String)? = { "Список обновлён" },
    ) {
        rule.setContent {
            LerNetTheme {
                ExpertScreen(
                    ui,
                    remember { SnackbarHostState() },
                    {}, {}, {}, {}, {}, false,
                    // Stub so advanced SOCKS/HTTP stays a TextButton, not a primary CTA.
                    onSaveExternal = { _, _ -> null },
                    onCopyProfile = onCopyProfile,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder,
                    onRefreshSubscription = onRefreshSubscription,
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

    @Test
    @Config(qualifiers = "w390dp-h1800dp-xxhdpi")
    fun configsSubscription() {
        expert(ExpertSection.EXITS, ExpertUiState(subscriptionRuntime, subscriptionBundle))
        rule.onNodeWithTag("expert_exits_configs").assertIsDisplayed()
        rule.onNodeWithTag("expert_folder_work").assertIsDisplayed()
        rule.onNodeWithTag("expert_folder_sub").assertIsDisplayed()
        rule.onNodeWithTag("expert_subscription_icon_sub").assertIsDisplayed()
        rule.onNodeWithTag("expert_config_row_sg").assertIsDisplayed()
        rule.onNodeWithTag("expert_config_row_jp").assertIsDisplayed()
        rule.onNodeWithTag("expert_config_row_us").assertIsDisplayed()
        capture("05-configs-subscription.png")
    }


    @Test
    @Config(qualifiers = "w390dp-h1800dp-xxhdpi")
    fun configsSubscriptionConfigCopy() {
        expert(ExpertSection.EXITS, ExpertUiState(subscriptionRuntime, subscriptionBundle))
        rule.onNodeWithTag("expert_config_row_sg").assertIsDisplayed().performClick()
        rule.onNodeWithTag("expert_subscription_copy").assertIsDisplayed()
        rule.onNodeWithText("Этот конфиг из подписки", substring = true).assertIsDisplayed()
        capture("07-subscription-config-copy.png")
    }
}
