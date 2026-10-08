package app.lernet.ui.expert

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExpertConnectionObservation
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.FlowCounters
import app.lernet.engine.policy.FlowInspection
import app.lernet.engine.policy.FlowTrafficRate
import app.lernet.engine.policy.FlowTrafficSampler
import app.lernet.ui.components.PanelCard
import app.lernet.ui.routes.InstalledApplicationIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class ExpertActivityFilter(val label: Int) {
    ALL(R.string.expert_activity_all),
    ACTIVE(R.string.expert_activity_active),
    CLOSED(R.string.expert_activity_closed),
    UNKNOWN(R.string.expert_activity_unknown),
}

internal fun filterExpertConnections(
    connections: List<ExpertConnectionObservation>,
    query: String,
    filter: ExpertActivityFilter,
): List<ExpertConnectionObservation> = connections.filter { connection ->
    val stateMatches = when (filter) {
        ExpertActivityFilter.ALL -> true
        ExpertActivityFilter.ACTIVE -> connection.active == true
        ExpertActivityFilter.CLOSED -> connection.active == false
        ExpertActivityFilter.UNKNOWN -> connection.active == null
    }
    val search = query.trim()
    stateMatches &&
        (
            search.isEmpty() ||
                listOfNotNull(connection.application, connection.destination, connection.domain)
                    .any { it.contains(search, ignoreCase = true) }
            )
}

internal fun isLongLivedConnection(connection: ExpertConnectionObservation, nowMs: Long): Boolean =
    connection.active == true && connection.startedAtMs?.let { nowMs - it >= 30_000 } == true

@Composable
internal fun ExpertActivity(
    runtime: ExpertRuntimeState,
    bundle: TransferBundle,
    onSimulate: (ExpertConnectionObservation, Boolean) -> Unit = { _, _ -> },
    onClear: () -> Unit = {},
    header: @Composable (FlowTrafficRate?) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ExpertActivityFilter.ALL) }
    var detail by remember { mutableStateOf<ExpertConnectionObservation?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(runtime.tun, runtime.connections.any { it.active == true }) {
        now = System.currentTimeMillis()
        if (runtime.connections.none { it.active == true }) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val connections = filterExpertConnections(runtime.connections, query, filter).sortedWith(
        compareByDescending<ExpertConnectionObservation> { it.startedAtMs ?: 0L }
            .thenByDescending { it.id.toLongOrNull() ?: 0L }
    )
    val recent = connections.filterNot { isLongLivedConnection(it, now) }
    val lasting = connections.filter { isLongLivedConnection(it, now) }
    val sampler = remember(runtime.tun) { FlowTrafficSampler() }
    val current = runtime.connections.filter { runtime.tun != null && it.identity == runtime.tun }
    val rates = remember(runtime.connections, runtime.tun) {
        sampler.update(current.map { FlowCounters(it.id, it.startedAtMs, it.observedAtMs, it.uploadedBytes, it.downloadedBytes) })
    }
    fun fresh(connection: ExpertConnectionObservation): Boolean = connection.observedAtMs?.let { now - it in 0L..6_000L } == true
    val active = current.filter { it.active == true }
    val totalRate = if (!runtime.connectionHistoryTruncated && active.isNotEmpty() && active.all { fresh(it) && it.id in rates }) {
        FlowTrafficRate(active.sumOf { rates.getValue(it.id).upload }, active.sumOf { rates.getValue(it.id).download })
    } else {
        null
    }
    var group by rememberSaveable { mutableStateOf(0) }
    val panelStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val panelHeight = (maxHeight * .85f).coerceIn(200.dp, 760.dp)
        @Composable fun panel(key: String, flows: List<ExpertConnectionObservation>, modifier: Modifier) {
            panelStates.SaveableStateProvider(key) {
                ActivityTrafficPanel(if (key == "recent") "Недавние / разовые" else "Длительные · от 30 с",
                    flows, modifier.height(panelHeight), runtime.tun) { connection ->
                    ActivityTrafficRow(connection, runtime, rates, now) { detail = connection }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { header(totalRate) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.expert_activity_search)) }
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExpertActivityFilter.entries.forEach { option ->
                            FilterChip(filter == option, { filter = option }, label = { Text(stringResource(option.label)) })
                        }
                    }
                    ExpertHint(R.string.expert_long_hint)
                    Text(
                        stringResource(R.string.expert_history_limit, runtime.connectionHistoryLimit),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (runtime.connectionHistoryTruncated) {
                        Text(
                            stringResource(R.string.expert_history_partial, runtime.connectionDroppedCount),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    TextButton(onClear, enabled = runtime.connections.isNotEmpty()) { Text(stringResource(R.string.expert_history_clear)) }
                }
            }
            if (!wide) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(group == 0, { group = 0 }, label = { Text("Разовые · ${recent.size}") })
                    FilterChip(group == 1, { group = 1 }, label = { Text("Длительные · ${lasting.size}") })
                }
            }
            item(key = "traffic") {
                if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    panel("recent", recent, Modifier.weight(1f))
                    panel("lasting", lasting, Modifier.weight(1f))
                } else if (group == 0) panel("recent", recent, Modifier.fillMaxWidth())
                else panel("lasting", lasting, Modifier.fillMaxWidth())
            }
        }
    }
    detail?.let { selected ->
        val connection = runtime.connections.firstOrNull { it.id == selected.id && it.identity == selected.identity }
            ?: selected.copy(active = selected.active.takeIf { it == false })
        AlertDialog(
            onDismissRequest = { detail = null }, title = { Text(stringResource(R.string.expert_flow_details)) },
            text = {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExpertConnectionDetails(connection, runtime, bundle)
                    TextButton({
                        detail = null
                        onSimulate(connection, false)
                    }) { Text(stringResource(R.string.expert_flow_simulate)) }
                    TextButton({
                        detail = null
                        onSimulate(connection, true)
                    }) { Text(stringResource(R.string.expert_flow_path)) }
                }
            }, confirmButton = { TextButton({ detail = null }) { Text(stringResource(R.string.expert_close)) } }
        )
    }
}

@Composable
internal fun ExpertConnectionDetails(connection: ExpertConnectionObservation, runtime: ExpertRuntimeState, bundle: TransferBundle) {
    var content by rememberSaveable(connection.identity, connection.id) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!content, { content = false }, label = { Text("Сведения") })
        FilterChip(content, { content = true }, label = { Text("Данные и передачи") })
    }
    if (content) {
        ActivityFlowContent(connection.inspection, connection.network, connection.sniffedProtocol)
        return
    }
    val unknown = stringResource(R.string.expert_value_unknown)
    val policy = listOfNotNull(runtime.appliedPolicy, runtime.saved).firstOrNull { it.revision == connection.policyRevision }
    Text(connection.application ?: stringResource(R.string.expert_unknown_app))
    Text(stringResource(R.string.expert_flow_source, endpointTitle(connection.sourceIp, connection.sourcePort, unknown)))
    Text(stringResource(R.string.expert_flow_destination, endpointTitle(connection.destinationIp, connection.destinationPort, unknown)))
    Text(connection.destination)
    Text(stringResource(R.string.expert_flow_domain, connection.domain ?: unknown))
    Text(stringResource(R.string.expert_flow_transport, connection.network ?: unknown, connection.sniffedProtocol ?: unknown))
    Text("${connectionStateTitle(connection.active)} · ${connection.decision}")
    connection.errorReason?.let { Text(flowErrorTitle(it), color = MaterialTheme.colorScheme.error) }
    connection.errorStage?.let {
        Text(
            stringResource(
                when (it) {
                    "dns" -> R.string.expert_stage_dns
                    "route" -> R.string.expert_stage_route
                    "dial" -> R.string.expert_stage_dial
                    "transfer" -> R.string.expert_stage_transfer
                    else -> R.string.expert_stage_connection
                }
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
    connection.closeReason?.let {
        val resource = when (it) {
            "finished" -> R.string.expert_close_finished
            "idle_timeout" -> R.string.expert_close_idle_timeout
            "reset" -> R.string.expert_close_reset
            else -> null
        }
        Text(resource?.let { id -> stringResource(id) } ?: it, style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.expert_connection_started, observationTimeTitle(connection.startedAtMs)))
    Text(stringResource(R.string.expert_flow_observed, observationTimeTitle(connection.observedAtMs)))
    Text(stringResource(R.string.expert_flow_updated, observationTimeTitle(connection.lastUpdateAtMs)))
    Text(stringResource(R.string.expert_flow_closed, observationTimeTitle(connection.closedAtMs)))
    Text(stringResource(R.string.expert_observation_bytes, connection.uploadedBytes, connection.downloadedBytes))
    Text(stringResource(R.string.expert_observation_revision, connection.policyRevision?.toString() ?: unknown))
    connection.exit?.let { Text(stringResource(R.string.expert_connection_exit, exitTitle(it, bundle, policy))) }
    if (policy == null) ExpertHint(R.string.expert_simulation_historical_limit)
    val names = policy?.let { (listOf(it.device) + it.trees).flatMap { tree -> tree.nodes }.associateBy { it.id } }.orEmpty()
    connection.nodeIds.forEach { id -> Text(names[id]?.title?.ifBlank { stringResource(R.string.expert_rule_unnamed) } ?: id) }
}

private fun endpointTitle(ip: String?, port: Int?, unknown: String): String = "${ip ?: unknown}:${port?.toString() ?: unknown}"

@Composable
private fun flowErrorTitle(code: String): String {
    val resource = when (code) {
        "timeout" -> R.string.expert_error_timeout
        "name_not_found" -> R.string.expert_error_name_not_found
        "resolution_failed" -> R.string.expert_error_resolution
        "network_error" -> R.string.expert_error_network
        "system_route_unavailable" -> R.string.expert_error_route_unavailable
        "system_route_ipv4_unavailable" -> R.string.expert_error_route_ipv4
        "system_route_ipv6_unavailable" -> R.string.expert_error_route_ipv6
        "system_route_manager_unavailable" -> R.string.expert_error_route_manager
        "system_route_snapshot_failed" -> R.string.expert_error_route_snapshot
        "system_route_bind_failed" -> R.string.expert_error_route_bind
        "ingress_not_ready" -> R.string.expert_error_ingress_not_ready
        "ingress_ready_timeout" -> R.string.expert_error_ingress_timeout
        "system_route_destination_invalid" -> R.string.expert_error_route_destination
        "system_route_changed" -> R.string.expert_error_route_changed
        "interface_binding_invalid" -> R.string.expert_error_binding_invalid
        "interface_binding_unavailable" -> R.string.expert_error_binding_unavailable
        "interface_binding_identity_changed" -> R.string.expert_error_binding_changed
        "interface_binding_owned_ingress" -> R.string.expert_error_binding_loop
        else -> null
    }
    return resource?.let { stringResource(it) + " ($code)" } ?: stringResource(R.string.expert_error_unknown, code)
}

@Composable
private fun ActivityTrafficRow(
    connection: ExpertConnectionObservation,
    runtime: ExpertRuntimeState,
    rates: Map<String, FlowTrafficRate>,
    now: Long,
    onDetails: () -> Unit,
) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InstalledApplicationIcon(connection.packageNames.singleOrNull())
            Text(
                connection.application ?: stringResource(R.string.expert_unknown_app),
                modifier = Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(connection.destination, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${connection.network ?: stringResource(R.string.expert_value_unknown)} · " +
                "${connectionStateTitle(connection.active)} · ${connection.decision}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.expert_observation_bytes, connection.uploadedBytes, connection.downloadedBytes),
            style = MaterialTheme.typography.labelSmall
        )
        rates[connection.id]?.takeIf {
            connection.active == true &&
                (connection.observedAtMs?.let { now - it in 0L..6_000L } == true) &&
                connection.identity == runtime.tun
        }?.let {
            Text(stringResource(R.string.expert_flow_rate, it.upload, it.download), style = MaterialTheme.typography.labelSmall)
        }
        val started = connection.startedAtMs
        val end = connection.closedAtMs ?: now.takeIf { connection.active == true }
        Text(
            if (started == null || end == null) {
                stringResource(R.string.expert_flow_unknown_duration)
            } else {
                stringResource(R.string.expert_flow_duration, ((end - started).coerceAtLeast(0) / 1_000))
            },
            style = MaterialTheme.typography.labelSmall
        )
        TextButton(onDetails) { Text(stringResource(R.string.expert_flow_details)) }
    }
}

@Composable
private fun ActivityTrafficPanel(
    title: String,
    flows: List<ExpertConnectionObservation>,
    modifier: Modifier,
    identity: Any?,
    row: @Composable (ExpertConnectionObservation) -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var live by rememberSaveable(identity) { mutableStateOf(true) }
    LaunchedEffect(list.interactionSource) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) live = false }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) }
            .collect { moved -> if (moved) live = false }
    }
    LaunchedEffect(live, flows.firstOrNull()?.id) { if (live) list.scrollToItem(0) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("$title · ${flows.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            FilterChip(live, {
                if (live) {
                    live = false
                } else {
                    scope.launch {
                        list.scrollToItem(0)
                        live = true
                    }
                }
            }, label = { Text(if (live) "● Live" else "↑ Live") })
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(), state = list,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (flows.isEmpty()) item { Text(stringResource(R.string.expert_activity_empty)) }
                items(flows, key = { "${it.identity}:${it.id}" }) { row(it) }
            }
            ActivityScrollRail(list)
        }
        Text(
            if (live) "Новые соединения сверху · Live" else "Слежение приостановлено",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ActivityScrollRail(list: LazyListState) {
    val scope = rememberCoroutineScope()
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier.width(48.dp).fillMaxHeight().semantics {
            contentDescription = "Прокрутка списка соединений"
            progressBarRangeInfo = ProgressBarRangeInfo(
                list.firstVisibleItemIndex.toFloat(),
                0f..(list.layoutInfo.totalItemsCount - 1).coerceAtLeast(1).toFloat()
            )
            setProgress { target ->
                val count = list.layoutInfo.totalItemsCount
                if (count > 0) scope.launch { list.scrollToItem(target.toInt().coerceIn(0, count - 1)) }
                count > 0
            }
        }.focusable().pointerInput(list) {
            var target = 0f
            detectVerticalDragGestures(onDragStart = { position ->
                target = position.y
            }, onVerticalDrag = { change, delta ->
                change.consume()
                target = (target + delta).coerceIn(0f, size.height.toFloat())
                val count = list.layoutInfo.totalItemsCount
                if (count > 0) {
                    scope.launch {
                        list.scrollToItem((target / size.height.coerceAtLeast(1) * (count - 1)).toInt().coerceIn(0, count - 1))
                    }
                }
            })
        }
    ) {
        val info = list.layoutInfo
        if (info.totalItemsCount > 0) {
            val ratio = (info.visibleItemsInfo.size.toFloat() / info.totalItemsCount).coerceIn(0f, 1f)
            val thumb = (size.height * ratio).coerceAtLeast(36.dp.toPx()).coerceAtMost(size.height)
            val fraction = list.firstVisibleItemIndex.toFloat() / (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            drawRoundRect(
                color.copy(alpha = .12f), Offset(size.width / 2 - 2.dp.toPx(), 0f),
                Size(4.dp.toPx(), size.height), CornerRadius(4.dp.toPx())
            )
            drawRoundRect(
                color.copy(alpha = .7f),
                Offset(
                    size.width / 2 - 3.dp.toPx(),
                    (size.height - thumb) * fraction.coerceIn(0f, 1f)
                ),
                Size(6.dp.toPx(), thumb), CornerRadius(4.dp.toPx())
            )
        }
    }
}

@Composable
private fun ActivityFlowContent(inspection: FlowInspection?, network: String?, protocol: String?) {
    var upload by rememberSaveable { mutableStateOf(true) }
    var hex by rememberSaveable { mutableStateOf(false) }
    if (inspection == null) {
        Text("Данных пока нет: передачи ещё не наблюдались, либо ядро не поддерживает просмотр.")
        return
    }
    val encrypted = inspection.encrypted(protocol)
    if (!inspection.payloadAvailable) {
        Text("Ядро передало только счётчики: содержимое и границы пакетов этого потока недоступны.",
            style = MaterialTheme.typography.bodySmall)
    }
    Text(if (encrypted) "Защищено шифрованием TLS/QUIC" else "Наблюдаемые байты", style = MaterialTheme.typography.titleSmall)
    Text(
        if (encrypted) {
            "Тело запросов скрыто. Ниже исходные байты, а не расшифрованные сообщения. Для расшифровки нужны ключи приложения или доверенный TLS-прокси."
        } else {
            "Первые ${FlowInspection.PREFIX_LIMIT} байт каждого направления: фрагмент, а не полный запрос. У открытого протокола видны заголовки и начало тела; бинарные данные смотрите в HEX."
        },
        style = MaterialTheme.typography.bodySmall
    )
    Text("Просмотр в памяти текущей сессии. В обычный журнал содержимое не записывается.", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(upload, { upload = true }, label = { Text("↑ От программы") })
        FilterChip(!upload, { upload = false }, label = { Text("↓ К программе") })
        FilterChip(hex, { hex = !hex }, label = { Text("HEX") })
    }
    Text(
        "Начало потока · ${inspection.bytes(upload).size}/${FlowInspection.PREFIX_LIMIT} байт",
        style = MaterialTheme.typography.labelSmall
    )
    SelectionContainer {
        Text(
            if (inspection.bytes(upload).isEmpty()) {
                "Содержимое не наблюдалось"
            } else if (hex || encrypted) {
                inspection.hex(upload)
            } else {
                inspection.text(upload)
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Text("Последние передачи · ${inspection.transfers.size} из ${inspection.transferCount}", style = MaterialTheme.typography.titleSmall)
    Text(
        if (!inspection.payloadAvailable) {
            "События счётчика; границы пакетов неизвестны."
        } else if (network == "udp") {
            "Строка — наблюдаемая UDP-датаграмма."
        } else {
            "Строка — чтение/запись TCP-потока, не отдельный пакет или сообщение."
        },
        style = MaterialTheme.typography.bodySmall
    )
    inspection.transfers.forEach { transfer ->
        Text(
            "${observationTimeTitle(transfer.atMs)} · ${if (transfer.upload) "↑" else "↓"} ${transfer.bytes} Б",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
