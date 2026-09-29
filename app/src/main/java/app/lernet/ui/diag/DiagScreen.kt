package app.lernet.ui.diag

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.BuildConfig
import app.lernet.LerNetApp
import app.lernet.R
import app.lernet.engine.TrafficDisplay
import app.lernet.engine.live.ChannelHealth
import app.lernet.engine.live.ChannelWatch
import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveConnStatus
import app.lernet.log.LogShare
import app.lernet.ui.components.PanelCard
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.layout.rememberCompactMetrics
import app.lernet.ui.routes.ink
import app.lernet.ui.routes.liveRouteTone
import app.lernet.ui.status.silenceBanner
import app.lernet.ui.status.silenceTitle
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.LerNetOk
import app.lernet.ui.theme.lernetButton
import java.util.Locale
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(
    state: DiagUiState,
    onIntent: (DiagIntent) -> Unit,
    onBack: () -> Unit,
    events: Flow<DiagEvent>,
    onOpenNetwork: () -> Unit = {},
) {
    val metrics = rememberCompactMetrics()
    var actionsOpen by remember { mutableStateOf(false) }
    var feedFull by remember { mutableStateOf(false) }
    ObserveDiagEvents(events)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onOpenNetwork) { Text("Сеть устройства") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = metrics.gutter),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            if (!feedFull) {
                Header(state, onIntent)
                TextButton(onClick = { actionsOpen = !actionsOpen }, modifier = Modifier.lernetButton()) {
                    Text(stringResource(if (actionsOpen) R.string.diag_actions_hide else R.string.diag_actions))
                }
                if (actionsOpen) {
                    Actions(onIntent)
                    Text(
                        stringResource(R.string.diag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { feedFull = true }, modifier = Modifier.lernetButton()) {
                    Text(stringResource(R.string.diag_feed_expand))
                }
            } else {
                TextButton(onClick = { feedFull = false }, modifier = Modifier.lernetButton()) {
                    Text(stringResource(R.string.diag_feed_collapse))
                }
            }
            val rows = debugSampleRows(state.rows)
            if (!state.vpnUp) {
                EmptyNote(stringResource(R.string.diag_need_vpn))
            } else if (rows.isEmpty()) {
                EmptyNote(stringResource(R.string.diag_empty))
            }
            DiagPages(state, rows, onIntent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ObserveDiagEvents(events: Flow<DiagEvent>) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.copied)
    val shareFailed = stringResource(R.string.logs_share_failed)
    val emptyRec = stringResource(R.string.diag_recording_empty)
    val idleRec = stringResource(R.string.diag_recording_idle)
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is DiagEvent.Copied -> {
                    clipboard.setText(AnnotatedString(event.text))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }
                DiagEvent.ShareLogs -> shareSession(context, shareFailed)
                is DiagEvent.ShareRecording -> shareRecording(context, event.text, shareFailed)
                is DiagEvent.Message -> {
                    val text = when (event.text) {
                        "empty" -> emptyRec
                        "idle" -> idleRec
                        else -> event.text
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private fun shareSession(context: Context, shareFailed: String) {
    runCatching {
        val store = (context.applicationContext as LerNetApp).logStore
        LogShare.shareSession(context, store)
    }.onFailure {
        Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
    }
}

private fun shareRecording(context: Context, text: String, shareFailed: String) {
    runCatching {
        LogShare.shareTextFile(
            context,
            text,
            "lernet-live.txt",
            context.getString(R.string.diag_export_recording),
        )
    }.onFailure {
        Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(state: DiagUiState, onIntent: (DiagIntent) -> Unit) {
    val dnsLabel = if (state.dnsOk) {
        stringResource(R.string.diag_dns_ok)
    } else {
        stringResource(R.string.diag_dns_dead)
    }
    val channelLabel = when (state.snapshot.channel) {
        ChannelHealth.UNKNOWN -> null
        ChannelHealth.HOP_UP -> stringResource(R.string.channel_up)
        ChannelHealth.HOP_DOWN -> stringResource(R.string.channel_down)
        ChannelHealth.HOP_LOST -> stringResource(R.string.channel_lost)
        ChannelHealth.DATA_STALLED -> stringResource(R.string.channel_stalled)
        ChannelHealth.PIPE_SILENT,
        ChannelHealth.TUNNEL_DEAD,
        -> silenceTitle(state.snapshot.channel, state.snapshot.pipeSilentCount, state.snapshot.pipeTunnelCount)
    }
    val recordCd = stringResource(R.string.diag_live_record)
    PanelCard {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.dnsOk,
                onClick = {},
                enabled = false,
                label = { Text(dnsLabel) },
            )
            if (channelLabel != null) {
                FilterChip(
                    selected = !ChannelWatch.isHonestlyUnhealthy(state.snapshot.channel),
                    onClick = {},
                    enabled = false,
                    label = { Text(channelLabel) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.diag_live_record), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = state.recording,
                    onCheckedChange = { onIntent(DiagIntent.SetRecording(it)) },
                    modifier = Modifier.semantics { contentDescription = recordCd },
                )
            }
        }
        SilenceNote(state)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(onIntent: (DiagIntent) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        OutlinedButton(
            onClick = { onIntent(DiagIntent.CopyLogs) },
            modifier = Modifier.fillMaxWidth().lernetButton(),
        ) {
            Icon(LerNetSymbols.copy(), contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.diag_copy_text))
        }
        OutlinedButton(
            onClick = { onIntent(DiagIntent.ShareLogs) },
            modifier = Modifier.fillMaxWidth().lernetButton(),
        ) {
            Icon(LerNetSymbols.share(), contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.share_last_log))
        }
        OutlinedButton(
            onClick = { onIntent(DiagIntent.ExportRecording) },
            modifier = Modifier.fillMaxWidth().lernetButton(),
        ) {
            Icon(LerNetSymbols.download(), contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.diag_export_recording))
        }
    }
}

@Composable
private fun DiagPages(
    state: DiagUiState,
    rows: List<LiveConn>,
    onIntent: (DiagIntent) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.page) { listState.scrollToItem(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
        Text(
            stringResource(R.string.diag_journal_size, state.journalBytes, state.journalCeiling),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onIntent(DiagIntent.NewerPage) },
                enabled = state.page > 0,
                modifier = Modifier.lernetButton(),
            ) {
                Text(stringResource(R.string.diag_page_next))
            }
            Text(stringResource(R.string.diag_page, state.page + 1, state.pageCount))
            TextButton(
                onClick = { onIntent(DiagIntent.OlderPage) },
                enabled = state.page + 1 < state.pageCount,
                modifier = Modifier.lernetButton(),
            ) {
                Text(stringResource(R.string.diag_page_prev))
            }
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
            modifier = Modifier.weight(1f),
        ) {
            items(rows, key = { it.id }) { row ->
                ConnRow(
                    row = row,
                    ping = if (state.pingId == row.id) state.pingText else null,
                    expanded = state.expandedId == row.id,
                    onClick = { onIntent(DiagIntent.Ping(row)) },
                    onExpand = { onIntent(DiagIntent.ToggleRow(row.id)) },
                )
            }
        }
    }
}

@Composable
private fun ConnRow(
    row: LiveConn,
    ping: String?,
    expanded: Boolean,
    onClick: () -> Unit,
    onExpand: () -> Unit,
) {
    val face = rememberAppFace(row.uid, row.pid, row.app)
    val tone = liveRouteTone(row.via, row.outbound, row.pipeLabel)
    ListItem(
        modifier = Modifier
            .drawBehind {
                val bar = 4.dp.toPx()
                drawRect(tone.ink(), size = Size(bar, size.height))
            }
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { DiagAppIcon(face) },
        headlineContent = {
            Column {
                Text(appFaceTitle(face), maxLines = 1, overflow = TextOverflow.Ellipsis, color = tone.ink())
                val pid = row.pid
                if (pid != null && face !is AppFace.Pid) {
                    PidLine(pid)
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    connPath(row),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                ConnDetails(row, ping, expanded, onExpand)
            }
        },
    )
}

@Composable
private fun SilenceNote(state: DiagUiState) {
    val channel = state.snapshot.channel
    val text = when (channel) {
        ChannelHealth.TUNNEL_DEAD,
        ChannelHealth.PIPE_SILENT,
        -> silenceBanner(channel, state.snapshot.pipeSilentCount, state.snapshot.pipeTunnelCount)
        ChannelHealth.UNKNOWN,
        ChannelHealth.HOP_UP,
        ChannelHealth.HOP_DOWN,
        ChannelHealth.HOP_LOST,
        ChannelHealth.DATA_STALLED,
        -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun debugSampleRows(live: List<LiveConn>): List<LiveConn> {
    if (!BuildConfig.DEBUG) return live
    val context = LocalContext.current
    val activity = context as? Activity ?: return live
    val enabled = activity.intent?.getBooleanExtra(DiagSample.EXTRA, false) == true
    if (!enabled) return live
    val extra = remember(context.packageName) { DiagSample.rows(context.packageManager, context.packageName) }
    return extra + live
}

@Composable
private fun connPath(row: LiveConn): String {
    val host = row.domain?.takeIf { it.isNotBlank() } ?: row.dest
    val rule = row.rule?.takeIf { it.isNotBlank() }
    val pipe = when {
        row.pipeLabel == "direct" -> stringResource(R.string.action_direct_help)
        row.pipeLabel == "proxy" -> stringResource(R.string.route_pipe_default)
        else -> row.pipeLabel
    }
    val status = when (row.status) {
        LiveConnStatus.OPEN -> stringResource(R.string.diag_status_open)
        LiveConnStatus.CLOSED -> stringResource(R.string.diag_status_ok)
        LiveConnStatus.UNFINISHED -> stringResource(R.string.diag_status_silent)
    }
    return if (rule == null) stringResource(R.string.diag_row_path_no_rule, host, pipe, status)
    else stringResource(R.string.diag_row_path, host, rule, pipe, status)
}

@Composable
private fun EmptyNote(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text,
            modifier = Modifier.padding(LerNetDimens.contentPadding),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConnDetails(row: LiveConn, ping: String?, expanded: Boolean, onExpand: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
        val domain = row.domain
        if (!domain.isNullOrBlank()) {
            Text(domain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        row.transport?.let {
            Text(stringResource(R.string.diag_transport, it.uppercase(Locale.ROOT)), style = MaterialTheme.typography.bodySmall)
        }
        row.protocol?.let { Text(stringResource(R.string.diag_protocol, it), style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onExpand, modifier = Modifier.lernetButton()) {
            val label = if (expanded) R.string.diag_snapshot_hide else R.string.diag_snapshot_show
            Text(stringResource(label))
        }
        if (expanded) {
            ConnSnapshotPanel(row)
        }
        if (!ping.isNullOrBlank()) {
            val fail = ping == stringResource(R.string.diag_ping_fail)
            Text(
                stringResource(R.string.diag_ping, ping),
                style = MaterialTheme.typography.bodySmall,
                color = if (fail) MaterialTheme.colorScheme.error else LerNetOk,
            )
        }
    }
}

@Composable
private fun ConnSnapshotPanel(row: LiveConn) {
    val model = DiagSnapshot.of(row)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val body = MaterialTheme.typography.bodySmall
    var contentOpen by remember(row.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
        Text(stringResource(R.string.diag_snap_dest, model.dest), style = body)
        model.domain?.let { Text(stringResource(R.string.diag_snap_domain, it), style = body) }
        val transport = listOfNotNull(model.transport, model.ipVersion?.let { "IPv$it" }).joinToString(" · ")
        if (transport.isNotEmpty()) Text(stringResource(R.string.diag_transport, transport), style = body)
        Text(
            stringResource(
                R.string.diag_snap_protocol,
                model.protocol ?: stringResource(R.string.diag_protocol_unavailable),
            ),
            style = body,
            color = if (model.protocol == null) muted else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(
                R.string.diag_snap_rule,
                model.rule ?: stringResource(R.string.diag_rule_unavailable),
            ),
            style = body,
            color = if (model.rule == null) muted else MaterialTheme.colorScheme.onSurface,
        )
        Text(stringResource(R.string.diag_snap_pipe, pipeLabel(model.pipe)), style = body)
        if (model.routeChain.isNotEmpty()) {
            Text(stringResource(R.string.diag_route_chain, model.routeChain.joinToString(" → ")), style = body)
        }
        Text(stringResource(R.string.diag_snap_status, statusLabel(model.status)), style = body)
        Text(
            stringResource(
                R.string.diag_snap_bytes,
                TrafficDisplay.formatBytes(model.uplink),
                TrafficDisplay.formatBytes(model.downlink),
            ),
            style = body,
        )
        model.uid?.let { Text(stringResource(R.string.diag_uid, it), style = body, color = muted) }
        model.pid?.let { PidLine(it) }
        if (model.hasCapturedContent) {
            TextButton(onClick = { contentOpen = !contentOpen }, modifier = Modifier.lernetButton()) {
                Text(stringResource(if (contentOpen) R.string.diag_payload_hide else R.string.diag_payload_show))
            }
            if (contentOpen) {
                model.requestLine?.let { Text(stringResource(R.string.diag_snap_path, it), style = body) }
                model.headers?.let { Text(stringResource(R.string.diag_snap_headers, it), style = body) }
                model.body?.let { Text(stringResource(R.string.diag_snap_body, it), style = body) }
                if (model.bodyTruncated) Text(stringResource(R.string.diag_body_truncated), style = body, color = muted)
            }
        } else {
            Text(
                stringResource(
                    if (model.wire == DiagSnapshot.Wire.TLS) R.string.diag_payload_encrypted
                    else R.string.diag_payload_unavailable,
                ),
                style = body,
                color = muted,
            )
        }
    }
}

@Composable
private fun pipeLabel(pipe: String): String = when (pipe) {
    "direct" -> stringResource(R.string.action_direct_help)
    "proxy" -> stringResource(R.string.route_pipe_default)
    else -> pipe
}

@Composable
private fun statusLabel(status: LiveConnStatus): String = when (status) {
    LiveConnStatus.OPEN -> stringResource(R.string.diag_status_open)
    LiveConnStatus.CLOSED -> stringResource(R.string.diag_status_ok)
    LiveConnStatus.UNFINISHED -> stringResource(R.string.diag_status_silent)
}
