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
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import app.lernet.engine.live.LiveVia
import app.lernet.ui.routes.RouteTone
import app.lernet.ui.theme.LerNetOk
import app.lernet.ui.theme.lernetButton
import java.util.Locale
import kotlinx.coroutines.flow.Flow

/** Audit J7 (mockup m5): what the user filters by — through the VPN, direct, no reply. */
internal enum class JournalFilter(val label: Int) {
    ALL(R.string.journal_all), VPN(R.string.journal_vpn), DIRECT(R.string.journal_direct), SILENT(R.string.journal_silent),
}

internal fun journalTone(row: LiveConn): RouteTone = liveRouteTone(row.via, row.outbound, row.pipeLabel)

internal fun journalMatches(row: LiveConn, title: String, query: String, filter: JournalFilter): Boolean {
    val tone = journalTone(row)
    val kind = when (filter) {
        JournalFilter.ALL -> true
        JournalFilter.VPN -> tone == RouteTone.VIA || tone == RouteTone.NAMED
        JournalFilter.DIRECT -> tone == RouteTone.DIRECT
        JournalFilter.SILENT -> row.status == LiveConnStatus.UNFINISHED
    }
    val q = query.trim()
    return kind && (q.isEmpty() || listOfNotNull(title, row.domain, row.dest, row.app, row.rule).any { it.contains(q, ignoreCase = true) })
}

/**
 * Audit J1–J8 (mockup m5): «Журнал запросов» — search and filter chips instead of pages and wide buttons,
 * ⏸ for the live record, actions in ⋮, details in a bottom sheet. Status is the strip above (G1).
 */
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
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(JournalFilter.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // J4: the bar slides away while the list scrolls and comes back on the way up.
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    ObserveDiagEvents(events)
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
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
                    IconButton(onClick = { onIntent(DiagIntent.SetRecording(!state.recording)) }, Modifier.testTag("journal_live")) {
                        Icon(
                            if (state.recording) LerNetSymbols.pause() else LerNetSymbols.record(),
                            contentDescription = stringResource(if (state.recording) R.string.journal_live_pause else R.string.journal_live_resume),
                            tint = if (state.recording) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { menu = true }, Modifier.testTag("journal_more")) {
                        Icon(LerNetSymbols.more(), contentDescription = stringResource(R.string.journal_more))
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.journal_copy)) }, onClick = { menu = false; onIntent(DiagIntent.CopyLogs) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.journal_share)) }, onClick = { menu = false; onIntent(DiagIntent.ShareLogs) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.journal_save_record)) }, onClick = { menu = false; onIntent(DiagIntent.ExportRecording) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.expert_device_network)) }, onClick = { menu = false; onOpenNetwork() })
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val rows = debugSampleRows(state.rows)
        val packages = context.packageManager
        val titles = remember(rows) { HashMap<String, String>() }
        val system = stringResource(R.string.diag_system)
        val uidTitle = stringResource(R.string.diag_uid, 0)
        fun title(row: LiveConn): String = titles.getOrPut(row.id) {
            when (val face = resolveAppFace(packages, row.uid, row.pid, row.app)) {
                is AppFace.Installed -> face.label
                AppFace.System -> system
                is AppFace.Uid -> uidTitle.replace("0", face.uid.toString())
                is AppFace.Pid -> "pid ${face.pid}"
            }
        }
        val visible = rows.filter { journalMatches(it, title(it), query, filter) }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = metrics.gutter).testTag("journal_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().testTag("journal_search"), singleLine = true,
                    placeholder = { Text(stringResource(R.string.journal_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JournalFilter.entries.forEach { option ->
                        FilterChip(filter == option, { filter = option }, label = { Text(stringResource(option.label)) },
                            modifier = Modifier.testTag("journal_filter_${option.name.lowercase()}"))
                    }
                }
            }
            item { SilenceNote(state) }
            if (state.recording) item {
                Text(stringResource(R.string.journal_live_on), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            when {
                !state.vpnUp && rows.isEmpty() -> item { EmptyNote(stringResource(R.string.diag_need_vpn)) }
                rows.isEmpty() -> item { EmptyNote(stringResource(R.string.diag_empty)) }
                visible.isEmpty() -> item { EmptyNote(stringResource(R.string.journal_nothing_found)) }
            }
            items(visible, key = { it.id }) { row ->
                JournalRow(row, title(row)) { selectedId = row.id }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
            if (rows.isNotEmpty()) item {
                Text(stringResource(R.string.journal_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        val selected = rows.firstOrNull { it.id == selectedId }
        if (selected != null) {
            ModalBottomSheet(onDismissRequest = { selectedId = null }) {
                JournalDetails(selected, title(selected), if (state.pingId == selected.id) state.pingText else null) {
                    onIntent(DiagIntent.Ping(selected))
                }
            }
        }
    }
}

@Composable
private fun JournalRow(row: LiveConn, title: String, onClick: () -> Unit) {
    val face = rememberAppFace(row.uid, row.pid, row.app)
    val tone = journalTone(row)
    val host = row.domain?.takeIf { it.isNotBlank() } ?: row.dest
    val tail = when {
        row.status == LiveConnStatus.UNFINISHED -> stringResource(R.string.journal_no_reply)
        !row.rule.isNullOrBlank() -> stringResource(R.string.journal_rule, row.rule!!)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DiagAppIcon(face)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(host, tail).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        JournalBadge(tone)
    }
}

@Composable
private fun JournalBadge(tone: RouteTone) {
    val (label, color) = when (tone) {
        RouteTone.VIA, RouteTone.NAMED -> stringResource(R.string.journal_badge_vpn) to MaterialTheme.colorScheme.primary
        RouteTone.DIRECT -> stringResource(R.string.journal_badge_direct) to MaterialTheme.colorScheme.tertiary
        RouteTone.BLOCK -> stringResource(R.string.journal_badge_block) to MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .14f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** Audit J5/J8: plain words and one bottom sheet, the same facts as before. */
@Composable
private fun JournalDetails(row: LiveConn, title: String, ping: String?, onPing: () -> Unit) {
    val model = DiagSnapshot.of(row)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var contentOpen by remember(row.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("journal_details"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiagAppIcon(rememberAppFace(row.uid, row.pid, row.app))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            JournalBadge(journalTone(row))
        }
        DetailLine(stringResource(R.string.journal_where), model.dest)
        model.domain?.let { DetailLine(stringResource(R.string.journal_site), it) }
        DetailLine(stringResource(R.string.journal_how), when (journalTone(row)) {
            RouteTone.DIRECT -> stringResource(R.string.journal_via_direct)
            RouteTone.NAMED -> stringResource(R.string.journal_via_named, model.pipe)
            RouteTone.BLOCK -> stringResource(R.string.journal_badge_block)
            RouteTone.VIA -> stringResource(R.string.journal_via_vpn)
        })
        DetailLine(stringResource(R.string.journal_rule_title), model.rule ?: stringResource(R.string.diag_rule_unavailable))
        DetailLine(stringResource(R.string.journal_state), when (model.status) {
            LiveConnStatus.OPEN -> stringResource(R.string.diag_status_open)
            LiveConnStatus.CLOSED -> stringResource(R.string.diag_status_ok)
            LiveConnStatus.UNFINISHED -> stringResource(R.string.diag_status_silent)
        })
        DetailLine(stringResource(R.string.journal_bytes), "↑ ${TrafficDisplay.formatBytes(model.uplink)} · ↓ ${TrafficDisplay.formatBytes(model.downlink)}")
        val transport = listOfNotNull(model.transport, model.ipVersion?.let { "IPv$it" }).joinToString(" · ")
        if (transport.isNotEmpty()) DetailLine(stringResource(R.string.journal_kind), transport)
        model.protocol?.let { DetailLine(stringResource(R.string.journal_app_protocol), it) }
        if (model.routeChain.isNotEmpty()) Text(stringResource(R.string.diag_route_chain, model.routeChain.joinToString(" → ")),
            style = MaterialTheme.typography.bodySmall, color = muted)
        model.uid?.let { Text("uid $it", style = MaterialTheme.typography.bodySmall, color = muted) }
        model.pid?.let { PidLine(it) }
        if (model.hasCapturedContent) {
            TextButton(onClick = { contentOpen = !contentOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (contentOpen) R.string.diag_payload_hide else R.string.diag_payload_show))
            }
            if (contentOpen) {
                val body = MaterialTheme.typography.bodySmall
                model.requestLine?.let { Text(stringResource(R.string.diag_snap_path, it), style = body) }
                model.headers?.let { Text(stringResource(R.string.diag_snap_headers, it), style = body) }
                model.body?.let { Text(stringResource(R.string.diag_snap_body, it), style = body) }
                if (model.bodyTruncated) Text(stringResource(R.string.diag_body_truncated), style = body, color = muted)
            }
        } else {
            Text(stringResource(if (model.wire == DiagSnapshot.Wire.TLS) R.string.diag_payload_encrypted else R.string.diag_payload_unavailable),
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
        OutlinedButton(onClick = onPing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("journal_ping")) {
            Text(stringResource(R.string.journal_check))
        }
        if (!ping.isNullOrBlank()) {
            val fail = ping == stringResource(R.string.diag_ping_fail)
            Text(stringResource(R.string.diag_ping, ping), style = MaterialTheme.typography.bodyMedium,
                color = if (fail) MaterialTheme.colorScheme.error else LerNetOk)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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

