package app.lernet.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.CompositionLocalProvider

import android.app.Activity
import androidx.activity.compose.LocalActivity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.saveable.rememberSaveable
import app.lernet.ui.expert.ExpertSection
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.lernet.BuildConfig
import app.lernet.LerNetApp
import app.lernet.R
import app.lernet.config.repo.RouteOwners
import app.lernet.engine.ConnectionCause
import app.lernet.engine.ConnectionState
import app.lernet.engine.log.CrashTrail
import app.lernet.log.LogShare
import app.lernet.ui.config.ConfigEditorScreen
import app.lernet.ui.config.ConfigEditorViewModel
import app.lernet.ui.expert.ExpertRoute
import app.lernet.ui.expert.ExpertViewModel
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.ui.diag.DiagScreen
import app.lernet.ui.network.NetworkObservationRoute
import app.lernet.ui.diag.DiagViewModel
import app.lernet.ui.home.ConfigDrawer
import app.lernet.ui.home.DrawerActions
import app.lernet.ui.home.HomeEvent
import app.lernet.ui.home.HomeIntent
import app.lernet.ui.home.HomeScreen
import app.lernet.ui.home.HomeUiState
import app.lernet.ui.home.HomeViewModel
import app.lernet.ui.importcfg.ImportScreen
import app.lernet.ui.importcfg.ImportViewModel
import app.lernet.ui.motion.motionTween
import app.lernet.ui.motion.rememberReduceMotion
import app.lernet.ui.routes.RouteEditorEvent
import app.lernet.ui.routes.RouteEditorScreen
import app.lernet.ui.routes.RouteOwnerChoice
import app.lernet.ui.routes.RouteEditorViewModel
import app.lernet.ui.settings.SettingsScreen
import app.lernet.ui.settings.SettingsViewModel
import app.lernet.ui.settings.AppsThroughVpnScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ExportRequest(val groupId: String?, val fileName: String)

@Composable
fun LerNetAppRoot() {
    app.lernet.ui.settings.AndroidUpdatePrompt()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    var expertContext by rememberSaveable { mutableStateOf(false) }
    val expertMode = when (entry?.destination?.route) {
        Dest.Expert.route -> true
        Dest.Home.route -> false
        else -> expertContext
    }
    // Audit N4: ☰ always opens the configs and folders, in both modes; Expert sections live in its bottom menu.
    // Audit G3: the Expert section is remembered for the whole session, a mode change does not reset it.
    var expertSection by rememberSaveable { mutableStateOf(ExpertSection.OVERVIEW) }
    LaunchedEffect(expertMode) { expertContext = expertMode }
    val expertNow = androidx.compose.runtime.rememberUpdatedState(expertMode)
    val homeViewModel: HomeViewModel = hiltViewModel()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerDragActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun openAppPage(route: String) {
        scope.launch { drawerState.close() }
        navController.navigate(route) { launchSingleTop = true }
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val shareFailed = stringResource(R.string.logs_share_failed)
    var pendingImportGroupId by remember { mutableStateOf<String?>(null) }
    var exportRequest by remember { mutableStateOf<ExportRequest?>(null) }
    var activeExportGroupId by remember { mutableStateOf<String?>(null) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) homeViewModel.exportTransfer(uri, activeExportGroupId)
        activeExportGroupId = null
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) homeViewModel.importTransfer(uri)
    }
    LaunchedEffect(homeViewModel) {
        homeViewModel.transferMessages.collect { snackbar.showSnackbar(it) }
    }
    exportRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { exportRequest = null },
            title = { Text(stringResource(R.string.transfer_warning_title)) },
            text = { Text(stringResource(R.string.transfer_warning_body)) },
            confirmButton = { TextButton(onClick = {
                activeExportGroupId = request.groupId
                exportRequest = null
                exportFile.launch(request.fileName)
            }) { Text(stringResource(R.string.transfer_save)) } },
            dismissButton = { TextButton(onClick = { exportRequest = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val shareLogs: () -> Unit = {
        runCatching {
            val store = (context.applicationContext as LerNetApp).logStore
            LogShare.shareSession(context, store)
        }.onFailure {
            Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
        }
    }
    var crashDialogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(homeState.offerLastLogs) {
        if (homeState.offerLastLogs) crashDialogOpen = true
    }
    PendingCrashDialog(
        visible = homeState.offerLastLogs && crashDialogOpen,
        onShare = {
            shareLogs()
            homeViewModel.onIntent(HomeIntent.DismissLastLogs)
            crashDialogOpen = false
        },
        onDismiss = { crashDialogOpen = false },
    )
    ObserveVpnPermission(homeViewModel)
    ObserveEngineFailure(homeState)
    ObserveDeleteUndo(homeViewModel, snackbar)
    DebugNavBootstrap(navController)
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !drawerDragActive,
        drawerContent = {
            ConfigDrawer(
                profiles = homeState.profiles,
                groups = homeState.groups,
                activeProfileId = homeState.activeProfile?.id,
                probes = homeState.probes,
                onDragActiveChange = { drawerDragActive = it },
                actions = drawerActions(
                    homeViewModel,
                    navController,
                    onImport = {
                        pendingImportGroupId = null
                        navController.navigate(Dest.Import.route)
                    },
                    onImportIntoGroup = { groupId ->
                        pendingImportGroupId = groupId
                        navController.navigate(Dest.Import.route)
                    },
                    onImportArchive = { importFile.launch(arrayOf("*/*")) },
                    onExportAll = { exportRequest = ExportRequest(null, "LerNET-all.lernet.json") },
                    onExportGroup = { groupId ->
                        val name = homeState.groups.firstOrNull { it.id == groupId }?.name.orEmpty()
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
                        exportRequest = ExportRequest(groupId, "LerNET-$name.lernet.json")
                    },
                    close = { scope.launch { drawerState.close() } },
                ).copy(managementOnly = expertMode),
            )
        },
    ) {
        val route = entry?.destination?.route
        val secondary = route != null && route != Dest.Home.route && route != Dest.Expert.route
        val expertViewModel: ExpertViewModel = hiltViewModel()
        val expertState by expertViewModel.state.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxSize()) {
          if (secondary) {
            // Audit G1: the connection status stays visible on every secondary screen.
            StatusStrip(
                stripStatus(homeState.snapshot, homeState.activeProfile?.name, expertState.runtime),
                onClick = {
                    val main = if (expertMode) Dest.Expert.route else Dest.Home.route
                    if (!navController.popBackStack(main, false)) navController.navigate(main) { launchSingleTop = true }
                },
            )
          }
          Box(Modifier.fillMaxSize().then(if (secondary) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
            CompositionLocalProvider(LocalAppSnackbar provides snackbar) {
            AppNavHost(
                navController = navController,
                homeViewModel = homeViewModel,
                homeState = homeState,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                expertSection = expertSection, onExpertSection = { expertSection = it },
                isExpert = { expertNow.value },
                onShareLogs = shareLogs,
                onImported = { ids ->
                    pendingImportGroupId?.let { homeViewModel.assignImportedToGroup(it, ids) }
                    pendingImportGroupId = null
                },
                showCrashBanner = homeState.offerLastLogs && !crashDialogOpen,
            )
            }
            // Audit G6: one snackbar queue for the whole app. Expert shows it above its bottom menu itself.
            if (route != Dest.Expert.route) SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
          }
        }
    }
}

/** Debug-only: `am start … --es lernet_nav routes/<profileId>` for soft-emu screenshots. */
@Composable
private fun DebugNavBootstrap(navController: NavHostController) {
    if (!BuildConfig.DEBUG) return
    val activity = LocalActivity.current ?: return
    val route = activity.intent?.getStringExtra(DEBUG_NAV_EXTRA)?.takeIf { it.isNotBlank() } ?: return
    LaunchedEffect(route) {
        delay(800)
        navController.navigate(route) {
            launchSingleTop = true
        }
    }
}

private const val DEBUG_NAV_EXTRA = "lernet_nav"

@Composable
private fun PendingCrashDialog(
    visible: Boolean,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_title)) },
        text = { Text(stringResource(R.string.crash_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onShare()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.share_last_log)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss_crash)) }
        },
    )
}

@Composable
private fun ObserveVpnPermission(homeViewModel: HomeViewModel) {
    val context = LocalContext.current
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        homeViewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> homeViewModel.onNotificationPermissionResult(granted) }
    LaunchedEffect(homeViewModel) {
        homeViewModel.events.collect { event ->
            when (event) {
                HomeEvent.RequestVpnPermission -> {
                    CrashTrail.mark("ui before VpnService.prepare")
                    val prepare = VpnService.prepare(context)
                    CrashTrail.mark("ui after VpnService.prepare needed=${prepare != null}")
                    if (prepare == null) {
                        homeViewModel.onVpnPermissionResult(true)
                    } else {
                        vpnLauncher.launch(prepare)
                    }
                }
                HomeEvent.RequestNotificationPermission -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        homeViewModel.onNotificationPermissionResult(true)
                    }
                }
                is HomeEvent.ProfileDeleted -> Unit
            }
        }
    }
}

@Composable
private fun ObserveDeleteUndo(homeViewModel: HomeViewModel, snackbar: SnackbarHostState) {
    val deleted = stringResource(R.string.profile_deleted)
    val undo = stringResource(R.string.undo)
    LaunchedEffect(homeViewModel) {
        homeViewModel.events.collect { event ->
            when (event) {
                is HomeEvent.ProfileDeleted -> {
                    val timeout = launch {
                        delay(5_000)
                        snackbar.currentSnackbarData?.dismiss()
                    }
                    val result = snackbar.showSnackbar(
                        message = deleted.format(event.name),
                        actionLabel = undo,
                        duration = SnackbarDuration.Indefinite,
                    )
                    timeout.cancel()
                    if (result == SnackbarResult.ActionPerformed) {
                        homeViewModel.onIntent(HomeIntent.UndoDelete)
                    }
                }
                HomeEvent.RequestVpnPermission, HomeEvent.RequestNotificationPermission -> Unit
            }
        }
    }
}

@Composable
private fun ObserveEngineFailure(homeState: HomeUiState) {
    val context = LocalContext.current
    val message = stringResource(R.string.engine_stub_toast)
    LaunchedEffect(homeState.snapshot.state, homeState.snapshot.cause) {
        val failed = homeState.snapshot.state == ConnectionState.FAILED
        if (failed && homeState.snapshot.cause is ConnectionCause.EngineUnavailable) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

private fun drawerActions(
    homeViewModel: HomeViewModel,
    navController: NavHostController,
    onImport: () -> Unit,
    onImportIntoGroup: (String) -> Unit,
    onImportArchive: () -> Unit,
    onExportAll: () -> Unit,
    onExportGroup: (String) -> Unit,
    close: () -> Unit,
): DrawerActions = DrawerActions(
    onSelect = {
        homeViewModel.onIntent(HomeIntent.SelectProfile(it))
        close()
    },
    onImport = {
        close()
        onImport()
    },
    onImportIntoGroup = { groupId ->
        close()
        onImportIntoGroup(groupId)
    },
    onImportArchive = { close(); onImportArchive() },
    onExportAll = { close(); onExportAll() },
    onExportGroup = { id -> close(); onExportGroup(id) },
    onCreateGroup = { name -> homeViewModel.onIntent(HomeIntent.CreateGroup(name)) },
    onGroupRoutes = { groupId ->
        close()
        navController.navigate(Dest.Routes.of(RouteOwners.group(groupId)))
    },
    onRoutes = { profileId ->
        close()
        navController.navigate(Dest.Routes.of(profileId))
    },
    onConfig = { profileId ->
        close()
        navController.navigate(Dest.Config.of(profileId))
    },
    onDelete = { homeViewModel.onIntent(HomeIntent.DeleteProfile(it)) },
    onDuplicate = { homeViewModel.onIntent(HomeIntent.DuplicateProfile(it)) },
    onRename = { id, name -> homeViewModel.onIntent(HomeIntent.RenameProfile(id, name)) },
    onProbe = { homeViewModel.onIntent(HomeIntent.ProbeProfiles(it)) },
    onMoveProfile = { id, groupId, index -> homeViewModel.onIntent(HomeIntent.MoveProfile(id, groupId, index)) },
    onRenameGroup = { id, name -> homeViewModel.onIntent(HomeIntent.RenameGroup(id, name)) },
    onDeleteGroup = { homeViewModel.onIntent(HomeIntent.DeleteGroup(it)) },
    onSetAutoFailover = { id, enabled -> homeViewModel.onIntent(HomeIntent.SetGroupAutoFailover(id, enabled)) },
    onMoveGroup = { id, index -> homeViewModel.onIntent(HomeIntent.MoveGroup(id, index)) },
    onReorderUngrouped = { id, beforeId -> homeViewModel.onIntent(HomeIntent.ReorderUngrouped(id, beforeId)) },
)

@Composable
private fun AppNavHost(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    homeState: HomeUiState,
    onOpenDrawer: () -> Unit,
    onShareLogs: () -> Unit,
    onImported: (List<String>) -> Unit,
    showCrashBanner: Boolean,
    expertSection: ExpertSection,
    onExpertSection: (ExpertSection) -> Unit,
    isExpert: () -> Boolean = { false },
) {
    val reduceMotion = rememberReduceMotion()
    val expertViewModel: ExpertViewModel = hiltViewModel()
    val expertState by expertViewModel.state.collectAsStateWithLifecycle()
    val enter = if (reduceMotion) {
        fadeIn(motionTween(true, 0))
    } else {
        fadeIn(motionTween(false, 200)) + slideInHorizontally(motionTween(false, 260)) { it / 6 }
    }
    val exit = if (reduceMotion) fadeOut(motionTween(true, 0)) else fadeOut(motionTween(false, 180))
    val popEnter = if (reduceMotion) fadeIn(motionTween(true, 0)) else fadeIn(motionTween(false, 200))
    val popExit = if (reduceMotion) {
        fadeOut(motionTween(true, 0))
    } else {
        fadeOut(motionTween(false, 180)) + slideOutHorizontally(motionTween(false, 240)) { it / 6 }
    }
    NavHost(
        navController = navController,
        startDestination = Dest.Home.route,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { popEnter },
        popExitTransition = { popExit },
    ) {
        composable(Dest.Home.route) {
            val expertActive = expertState.runtime?.phase in setOf(ExpertSessionPhase.RUNNING, ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
            HomeScreen(
                state = homeState,
                onIntent = { intent ->
                    if (expertActive && intent == HomeIntent.ToggleConnect) {
                        navController.navigate(Dest.Expert.route) { launchSingleTop = true }
                    } else {
                        homeViewModel.onIntent(intent)
                    }
                },
                onOpenDrawer = onOpenDrawer,
                onOpenSettings = { navController.navigate(Dest.Settings.route) },
                onOpenDiag = { navController.navigate(Dest.Diag.route) },
                onOpenRoutes = { ownerId -> navController.navigate(Dest.Routes.of(ownerId)) },
                onRefreshHop = homeViewModel::refreshHop,
                onOpenExpert = { navController.navigate(Dest.Expert.route) { launchSingleTop = true } },
                expertActive = expertActive,
                showCrashBanner = showCrashBanner,
            )
        }
        composable(Dest.Expert.route) {
            ExpertRoute(
                section = expertSection, onSectionChange = onExpertSection, onOpenDrawer = onOpenDrawer,
                onSettings = { navController.navigate(Dest.Settings.route) { launchSingleTop = true } },
                onNetwork = { navController.navigate(Dest.Network.route) { launchSingleTop = true } },
                onVpn = { navController.navigate(Dest.Home.route) { popUpTo(Dest.Home.route) { inclusive = true }; launchSingleTop = true } },
                simpleActive = homeState.snapshot.state in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING),
            )
        }
        secondaryDestinations(navController, homeViewModel, onShareLogs, onImported, isExpert)
    }
}

private fun NavGraphBuilder.secondaryDestinations(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    onShareLogs: () -> Unit,
    onImported: (List<String>) -> Unit,
    isExpert: () -> Boolean,
) {
    composable(Dest.Import.route) {
        val vm: ImportViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        ImportScreen(
            state = state,
            onIntent = vm::onIntent,
            onBack = { navController.popBackStack() },
            events = vm.events,
            onOpenRoutes = { navController.navigate(Dest.Routes.of(it)) },
            onSaved = onImported,
        )
    }
    composable(Dest.Settings.route) {
        val vm: SettingsViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        SettingsScreen(
            state = state,
            onIntent = vm::onIntent,
            onBack = { navController.popBackStack() },
            onShareLogs = onShareLogs,
            onOpenDiag = { navController.navigate(Dest.Diag.route) },
            onOpenNetwork = { navController.navigate(Dest.Network.route) { launchSingleTop = true } },
            onOpenApps = { navController.navigate(Dest.Apps.route) { launchSingleTop = true } },
            fromExpert = isExpert(),
        )
    }
    composable(Dest.Apps.route) {
        val vm: SettingsViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        AppsThroughVpnScreen(
            bypass = state.settings.engineDefaults.bypassApps.toSet(),
            onChange = { vm.onIntent(app.lernet.ui.settings.SettingsIntent.SetBypassApps(it)) },
            onBack = { navController.popBackStack() },
        )
    }
    composable(Dest.Diag.route) {
        val vm: DiagViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        DiagScreen(
            state = state,
            onIntent = vm::onIntent,
            onBack = { navController.popBackStack() },
            events = vm.events,
            onOpenNetwork = { navController.navigate(Dest.Network.route) },
        )
    }
    composable(Dest.Network.route) {
        NetworkObservationRoute(onBack = { navController.popBackStack() })
    }
    composable(
        Dest.Routes.route,
        arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
    ) {
        val vm: RouteEditorViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(vm) {
            vm.events.collect { event ->
                when (event) {
                    RouteEditorEvent.Leave -> {
                        navController.popBackStack()
                    }
                    is RouteEditorEvent.OpenGroupRoutes -> {
                        navController.navigate(
                            Dest.Routes.of(RouteOwners.group(event.groupId)),
                        ) {
                            popUpTo(Dest.Routes.route) { inclusive = true }
                        }
                    }
                }
            }
        }
        // Audit R4: whose rules these are is chosen in the editor header, as on Windows.
        val home by homeViewModel.state.collectAsStateWithLifecycle()
        val owners = home.profiles.map { RouteOwnerChoice(it.id, it.name) } +
            home.groups.map { RouteOwnerChoice(RouteOwners.group(it.id), it.name, folder = true) }
        RouteEditorScreen(
            state = state,
            onIntent = vm::onIntent,
            onBack = { navController.popBackStack() },
            owners = owners,
            onOwner = { owner ->
                if (owner != state.ownerId) {
                    navController.navigate(Dest.Routes.of(owner)) { popUpTo(Dest.Routes.route) { inclusive = true } }
                }
            },
        )
    }
    composable(
        Dest.Config.route,
        arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
    ) {
        val vm: ConfigEditorViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        val profileId = it.arguments?.getString("profileId").orEmpty()
        ConfigEditorScreen(
            state = state,
            onIntent = vm::onIntent,
            onBack = { navController.popBackStack() },
            onOpenRoutes = { navController.navigate(Dest.Routes.of(profileId)) },
            events = vm.events,
        )
    }
}
