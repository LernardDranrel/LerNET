package app.lernet.ui.home

import app.lernet.config.net.LocalProxyAccess

import androidx.compose.foundation.text.selection.SelectionContainer

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.LerNetApp
import app.lernet.R
import app.lernet.config.repo.RouteOwners
import app.lernet.engine.ConnectionCause
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import app.lernet.engine.TrafficDisplay
import app.lernet.engine.live.ChannelHealth
import app.lernet.engine.live.ChannelWatch
import app.lernet.engine.live.SilenceReport
import app.lernet.engine.net.OutboundEndpoint
import app.lernet.engine.policy.LinkHold
import app.lernet.log.LogShare
import app.lernet.ui.components.LerNetLogo
import app.lernet.ui.components.PanelCard
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.layout.rememberCompactMetrics
import app.lernet.ui.status.HopPathLine
import app.lernet.ui.status.silenceBanner
import app.lernet.ui.status.silenceTitle
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onIntent: (HomeIntent) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiag: () -> Unit,
    onOpenRoutes: (String) -> Unit,
    onRefreshHop: () -> Unit,
    onOpenExpert: (() -> Unit)? = null,
    expertActive: Boolean = false,
    showCrashBanner: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    var confirmExit by remember { mutableStateOf(false) }
    ObserveConnectionFeedback(snapshot.state)
    KeepAwakeWhileConnecting(isConnecting(snapshot.state))
    BackHandler(enabled = isSessionActive(snapshot.state)) { confirmExit = true }
    if (state.pendingSwitch != null) {
        SwitchConfirmSheet(name = state.pendingSwitch.name, onIntent = onIntent)
    }
    if (state.pendingMode != null) {
        ModeSwitchSheet(mode = state.pendingMode, onIntent = onIntent)
    }
    if (confirmExit) {
        ExitSessionDialog(onStay = { confirmExit = false }, onExit = { confirmExit = false })
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical),
        topBar = {
            Column {
                HomeTopBar(onOpenDrawer, onOpenSettings, onOpenDiag)
                // Audit N19 (mockup m1): the mode switch is a segmented button, not folder tabs.
                onOpenExpert?.let {
                    app.lernet.ui.expert.AppModeTabs(false, {}, it,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("home_mode_switch"))
                }
                if (expertActive && onOpenExpert != null) TextButton(onClick = onOpenExpert, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.expert_active_on_simple))
                }
            }
        },
    ) { padding ->
        HomeBody(
            state = state,
            onIntent = onIntent,
            onOpenDrawer = onOpenDrawer,
            onOpenRoutes = onOpenRoutes,
            onRefreshHop = onRefreshHop,
            onOpenDiag = onOpenDiag,
            showCrashBanner = showCrashBanner,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun HomeBody(
    state: HomeUiState,
    onIntent: (HomeIntent) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenRoutes: (String) -> Unit,
    onRefreshHop: () -> Unit,
    onOpenDiag: () -> Unit,
    showCrashBanner: Boolean,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val context = LocalContext.current
    val shareFailed = stringResource(R.string.logs_share_failed)
    val metrics = rememberCompactMetrics()
    val liveMode = if (isSessionActive(snapshot.state)) snapshot.mode else state.effectiveMode
    // Mockup m1: button and state without a frame, one config line, one attention zone, one «Связь» card, the journal row.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = metrics.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        ConnectionHero(
            connection = snapshot.state,
            status = when {
                snapshot.waitingForNetwork -> stringResource(R.string.state_waiting_network)
                snapshot.hold == LinkHold.ALL_SILENT -> snapshot.nextCheckAtMs?.let { at ->
                    val left = app.lernet.engine.policy.RecheckJitter.label((at - System.currentTimeMillis()).coerceAtLeast(0L))
                    // A no-break space keeps the time on one line, as on Windows (LinkJudge.holdMessage).
                    stringResource(R.string.state_hold_all_silent_next, left.replace(' ', '\u00A0'))
                } ?: stringResource(R.string.state_hold_all_silent)
                snapshot.hold == LinkHold.JAMMED -> stringResource(R.string.state_hold_jammed)
                snapshot.tried != null -> stringResource(R.string.state_holding_no_config)
                else -> phaseLabel(snapshot.state)
            },
            enabled = state.activeProfile != null || isSessionActive(snapshot.state),
            onToggle = { onIntent(HomeIntent.ToggleConnect) },
            onOpenRoutes = null,
            holding = snapshot.waitingForNetwork || snapshot.hold != null || snapshot.tried != null,
            // Holding: «Выключить VPN» is right below; failed: the error card has «Повторить».
            showActionCaption = snapshot.tried == null && snapshot.state != ConnectionState.FAILED,
        ) {}
        ProfileLine(
            name = state.activeProfile?.name,
            group = state.groups.firstOrNull { state.activeProfile?.id in it.profileIds },
            empty = state.profiles.isEmpty(),
            onChange = onOpenDrawer,
            onRules = homeRouteOwnerId(state)?.let { ownerId -> { onOpenRoutes(ownerId) } },
            proxyMode = state.effectiveMode == RunMode.PROXY,
        )
        // Audit H7: one zone, most important first; the rest fold behind «Ещё N».
        val attention = buildList<@Composable ColumnScope.() -> Unit> {
            if (snapshot.state == ConnectionState.FAILED) add {
                FailedCard(
                    cause = snapshot.cause,
                    onRetry = { onIntent(HomeIntent.ToggleConnect) },
                    onProfiles = onOpenDrawer,
                    onShareLogs = { shareSessionLogs(context, shareFailed) },
                )
            }
            snapshot.tried?.let { report ->
                add {
                    FailoverReportCard(
                        report,
                        groupName = report.groupName ?: state.groups.firstOrNull { state.activeProfile?.id in it.profileIds }?.name,
                        onOtherFolder = onOpenDrawer,
                        onTurnOff = { onIntent(HomeIntent.ToggleConnect) },
                    )
                }
            }
            if (snapshot.state == ConnectionState.CONNECTED && isTunnelSilence(snapshot.channel)) add { PipeSilentBanner(snapshot) }
            if (snapshot.state == ConnectionState.CONNECTED && snapshot.channel != ChannelHealth.UNKNOWN &&
                !isTunnelSilence(snapshot.channel) && snapshot.channel != ChannelHealth.HOP_UP) add { PanelCard { ChannelLine(snapshot) } }
            if (snapshot.banner != null) add { FailoverBannerCard(state, onIntent) }
            if (showCrashBanner && state.offerLastLogs) add {
                LastLogsBanner(
                    onShare = {
                        shareSessionLogs(context, shareFailed)
                        onIntent(HomeIntent.DismissLastLogs)
                    },
                    onDismiss = { onIntent(HomeIntent.DismissLastLogs) },
                )
            }
            if (state.skippedRules.isNotEmpty()) add { SkippedConditionsCard(state.skippedRules) }
            if (state.showLockdownHint) add {
                LockdownHintCard(
                    onOpenSettings = { openVpnSettings(context) },
                    onDismiss = { onIntent(HomeIntent.DismissLockdownHint) },
                )
            }
            if (state.showNotificationHint) add {
                NotificationHintCard(
                    onOpenSettings = { openNotificationSettings(context) },
                    onDismiss = { onIntent(HomeIntent.DismissNotificationHint) },
                )
            }
            // v2: the mode lives in Settings; the home screen only says when the proxy is on.
            if (state.effectiveMode == RunMode.PROXY) add { ProxyModeNote() }
            if (liveMode == RunMode.PROXY && state.effectiveMode != RunMode.PROXY) add { ProxyModeBanner() }
        }
        AttentionZone(attention)
        // While LerNET holds the tunnel the report card says what was tried; no configs: the line above says what to add.
        val holding = snapshot.tried != null
        val noConfigs = state.profiles.isEmpty()
        val selectedHost = state.activeProfile?.selectedOutbound()?.singBoxJson?.let(OutboundEndpoint::parse)?.host
        val hopSnapshot = if (snapshot.state == ConnectionState.DISCONNECTED && snapshot.hopHost.isNotBlank() &&
            selectedHost != null && !snapshot.hopHost.equals(selectedHost, ignoreCase = true)) {
            snapshot.copy(hops = emptyList(), hopHost = "", hopRunning = false)
        } else snapshot
        if (!holding && !noConfigs) LinkCard(state, onIntent, hopSnapshot, onRefreshHop)
        JournalRow(onOpenDiag)
        if (!state.engineAvailable) {
            Text(
                stringResource(R.string.engine_stub_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(LerNetDimens.itemGap))
    }
}

internal fun homeRouteOwnerId(state: HomeUiState): String? {
    val profileId = (if (isSessionActive(state.snapshot.state)) state.snapshot.activeProfileId else null)
        ?.takeIf { activeId -> state.profiles.any { it.id == activeId } }
        ?: state.activeProfile?.id
        ?: return null
    return state.groups.firstOrNull { profileId in it.profileIds }
        ?.let { RouteOwners.group(it.id) }
        ?: profileId
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit, onOpenDiag: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LerNetLogo(size = 28.dp)
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(LerNetSymbols.menu(), contentDescription = stringResource(R.string.cd_configs))
            }
        },
        actions = {
            IconButton(onClick = onOpenDiag) {
                Icon(LerNetSymbols.journal(), contentDescription = stringResource(R.string.cd_diag))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(LerNetSymbols.settings(), contentDescription = stringResource(R.string.cd_settings))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}


@Composable
private fun ChannelLine(snapshot: ConnectionSnapshot) {
    val channel = snapshot.channel
    val label = when (channel) {
        ChannelHealth.UNKNOWN -> return
        ChannelHealth.HOP_UP -> stringResource(R.string.channel_up)
        ChannelHealth.HOP_DOWN -> stringResource(R.string.channel_down)
        ChannelHealth.HOP_LOST -> stringResource(R.string.channel_lost)
        ChannelHealth.DATA_STALLED -> stringResource(R.string.channel_stalled)
        ChannelHealth.PIPE_SILENT,
        ChannelHealth.TUNNEL_DEAD,
        -> silenceTitle(channel, snapshot.pipeSilentCount, snapshot.pipeTunnelCount)
    }
    val color = if (ChannelWatch.isHonestlyUnhealthy(channel)) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
        if (channel == ChannelHealth.PIPE_SILENT || channel == ChannelHealth.TUNNEL_DEAD) {
            val report = ChannelWatch.silenceReport(channel, snapshot.pipeSilentCount, snapshot.pipeTunnelCount)
            val hint = if (report == SilenceReport.ALL) {
                R.string.channel_tunnel_dead_hint
            } else {
                R.string.channel_pipe_silent_hint
            }
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (channel == ChannelHealth.DATA_STALLED) {
            Text(
                stringResource(R.string.channel_pipe_silent_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (channel == ChannelHealth.HOP_UP) {
            Text(
                stringResource(R.string.channel_up_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun isTunnelSilence(channel: ChannelHealth): Boolean =
    channel == ChannelHealth.PIPE_SILENT || channel == ChannelHealth.TUNNEL_DEAD

@Composable
private fun PipeSilentBanner(snapshot: ConnectionSnapshot) {
    val text = when (snapshot.channel) {
        ChannelHealth.TUNNEL_DEAD,
        ChannelHealth.PIPE_SILENT,
        -> silenceBanner(snapshot.channel, snapshot.pipeSilentCount, snapshot.pipeTunnelCount)
        ChannelHealth.UNKNOWN,
        ChannelHealth.HOP_UP,
        ChannelHealth.HOP_DOWN,
        ChannelHealth.HOP_LOST,
        ChannelHealth.DATA_STALLED,
        -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(LerNetDimens.contentPadding),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}


@Composable
private fun LastLogsBanner(
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val crashCd = stringResource(R.string.crash_title)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = crashCd },
    ) {
        Column(
            Modifier.padding(LerNetDimens.contentPadding),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.crash_body), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
                TextButton(onClick = onShare, modifier = Modifier.lernetButton()) {
                    Text(stringResource(R.string.share_last_log))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.lernetButton()) {
                    Text(stringResource(R.string.dismiss_crash))
                }
            }
        }
    }
}

@Composable
private fun ProxyModeBanner() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(LerNetDimens.contentPadding),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.cardGap),
        ) {
            Text(stringResource(R.string.mode_proxy_banner_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.mode_proxy_banner_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FailedCard(
    cause: ConnectionCause?,
    onRetry: () -> Unit,
    onProfiles: () -> Unit,
    onShareLogs: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val detail = cause?.technicalDetail()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(LerNetDimens.contentPadding),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Text(cause?.titleRu() ?: stringResource(R.string.state_failed), style = MaterialTheme.typography.titleMedium)
            if (!detail.isNullOrBlank()) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.lernetButton(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Text(stringResource(if (expanded) R.string.hide_details else R.string.details))
                }
                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(detail))
                                copied = true
                            },
                            modifier = Modifier.lernetButton(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        ) {
                            Icon(LerNetSymbols.copy(), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(if (copied) R.string.copied else R.string.copy))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.weight(1f).lernetButton(),
                ) { Text(stringResource(R.string.retry)) }
                FilledTonalButton(onClick = onProfiles, modifier = Modifier.weight(1f).lernetButton()) {
                    Text(stringResource(R.string.to_profiles))
                }
            }
            TextButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth().lernetButton(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Text(stringResource(R.string.share_logs))
            }
        }
    }
}

@Composable
private fun FailoverBannerCard(state: HomeUiState, onIntent: (HomeIntent) -> Unit) {
    val banner = state.snapshot.banner ?: return
    val key = "${banner.fromName}->${banner.toName}"
    if (state.settings.dismissedBannerKey == key) return
    PanelCard {
        Text(
            stringResource(R.string.failover_banner, banner.fromName, banner.toName, banner.groupName),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = { onIntent(HomeIntent.DismissBanner) }, modifier = Modifier.lernetButton()) {
            Text(stringResource(R.string.dismiss))
        }
    }
}

@Composable
private fun LockdownHintCard(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    PanelCard {
        Text(stringResource(R.string.lockdown_hint_body), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.lernetButton()) {
                Text(stringResource(R.string.lockdown_hint_open))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.lernetButton()) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

@Composable
private fun NotificationHintCard(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    PanelCard {
        Text(stringResource(R.string.notification_hint_body), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.lernetButton()) {
                Text(stringResource(R.string.notification_hint_open))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.lernetButton()) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

private fun openVpnSettings(context: android.content.Context) {
    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
}

@Composable
internal fun ProxyAddressRow(access: LocalProxyAccess?) {
    if (access == null) return
    val clipboard = LocalClipboardManager.current
    PanelCard {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ProxyLine(stringResource(R.string.copy_proxy), access.address)
                ProxyLine(stringResource(R.string.proxy_login), access.username)
                ProxyLine(stringResource(R.string.proxy_password), access.password)
            }
        }
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(access.url())) },
            modifier = Modifier.fillMaxWidth().lernetButton(),
        ) {
            Icon(LerNetSymbols.copy(), contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.copy))
        }
    }
}

@Composable
private fun ProxyLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchConfirmSheet(name: String, onIntent: (HomeIntent) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(HomeIntent.DismissSwitch) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LerNetDimens.screenPadding, vertical = LerNetDimens.itemGap),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Text(stringResource(R.string.switch_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.switch_body, name), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { onIntent(HomeIntent.ConfirmSwitch) },
                modifier = Modifier.fillMaxWidth().lernetButton(),
            ) {
                Text(stringResource(R.string.switch_confirm))
            }
            TextButton(
                onClick = { onIntent(HomeIntent.DismissSwitch) },
                modifier = Modifier.fillMaxWidth().lernetButton(),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitchSheet(mode: RunMode, onIntent: (HomeIntent) -> Unit) {
    val modeName = if (mode == RunMode.PROXY) {
        stringResource(R.string.mode_proxy)
    } else {
        stringResource(R.string.mode_vpn)
    }
    ModalBottomSheet(
        onDismissRequest = { onIntent(HomeIntent.DismissModeSwitch) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LerNetDimens.screenPadding, vertical = LerNetDimens.itemGap),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Text(stringResource(R.string.mode_switch_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.mode_switch_body, modeName),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onIntent(HomeIntent.ConfirmModeSwitch) },
                modifier = Modifier.fillMaxWidth().lernetButton(),
            ) {
                Text(stringResource(R.string.mode_switch_confirm))
            }
            TextButton(
                onClick = { onIntent(HomeIntent.DismissModeSwitch) },
                modifier = Modifier.fillMaxWidth().lernetButton(),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun ObserveConnectionFeedback(state: ConnectionState) {
    val view = LocalView.current
    LaunchedEffect(state) {
        when (state) {
            ConnectionState.CONNECTED -> haptic(view, HapticFeedbackConstants.CONFIRM)
            ConnectionState.FAILED -> haptic(view, HapticFeedbackConstants.REJECT)
            ConnectionState.DISCONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING,
            -> Unit
        }
    }
}

@Composable
private fun KeepAwakeWhileConnecting(connecting: Boolean) {
    val view = LocalView.current
    DisposableEffect(connecting) {
        view.keepScreenOn = connecting
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun phaseLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.DISCONNECTED -> stringResource(R.string.state_disconnected)
        ConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
        ConnectionState.CONNECTED -> stringResource(R.string.state_connected)
        ConnectionState.RECONNECTING -> stringResource(R.string.state_reconnecting)
        ConnectionState.FAILED -> stringResource(R.string.state_failed)
    }

@Composable
private fun ExitSessionDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val activity = LocalView.current.context as? Activity
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.confirm_exit_title)) },
        text = { Text(stringResource(R.string.confirm_exit_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onExit()
                    activity?.moveTaskToBack(true)
                },
            ) { Text(stringResource(R.string.exit_app)) }
        },
        dismissButton = {
            TextButton(onClick = onStay) { Text(stringResource(R.string.stay)) }
        },
    )
}

private fun shareSessionLogs(context: Context, failedMessage: String) {
    runCatching {
        val store = (context.applicationContext as LerNetApp).logStore
        LogShare.shareSession(context, store)
    }.onFailure {
        Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
    }
}

private fun isConnecting(state: ConnectionState): Boolean =
    state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING

private fun haptic(view: View, type: Int) {
    val resolved =
        if (Build.VERSION.SDK_INT >= 30) {
            type
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
    view.performHapticFeedback(resolved)
}


/** Mockup 10 A: which configs were tried, why, and that traffic does not leak meanwhile. Same as on Windows. */
@Composable
private fun FailoverReportCard(
    report: app.lernet.engine.policy.FailoverReport,
    groupName: String?,
    onOtherFolder: () -> Unit,
    onTurnOff: () -> Unit,
) {
    val time = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    PanelCard(modifier = Modifier.testTag("failover_report")) {
        Text(
            groupName?.let { stringResource(R.string.failover_report_title_named, it) } ?: stringResource(R.string.failover_report_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.failover_report_tried), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        report.attempts.forEach { attempt ->
            // Name and time on one line, the reason in full under it, same as on Windows.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(attempt.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        java.time.Instant.ofEpochMilli(attempt.atMs).atZone(java.time.ZoneId.systemDefault()).format(time),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(attempt.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            (report.nextRetry?.let { stringResource(R.string.failover_report_next, it) } ?: stringResource(R.string.failover_report_auto)) +
                " " + stringResource(R.string.failover_report_no_leak),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOtherFolder, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.failover_other_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onTurnOff, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.failover_turn_off), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(R.string.failover_turn_off_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Mockup 10: a rule condition this platform cannot check is shown, not silently ignored. */
@Composable
private fun SkippedConditionsCard(rules: List<String>) {
    PanelCard(modifier = Modifier.testTag("skipped_conditions")) {
        Text(stringResource(R.string.skipped_conditions_title), style = MaterialTheme.typography.titleSmall)
        rules.take(5).forEach { title ->
            Text(stringResource(R.string.skipped_condition_android, title), style = MaterialTheme.typography.bodySmall)
        }
        if (rules.size > 5) Text("+${rules.size - 5}", style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.skipped_conditions_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
private fun ProxyModeNote() {
    Text(
        stringResource(R.string.home_proxy_mode_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("home_proxy_note"),
    )
}
