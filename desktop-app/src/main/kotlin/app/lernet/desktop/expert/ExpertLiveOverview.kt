@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.lernet.desktop.expert

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.engine.policy.DirectFamilyAvailability
import app.lernet.engine.policy.ExpertTunnelHealth
import app.lernet.engine.policy.FlowInspection
import app.lernet.ui.controls.NetworkLever
import app.lernet.ui.controls.NetworkLeverLamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ExpertLiveOverview(
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    modifier: Modifier,
    confirm: (ExpertIntent) -> Unit,
    simulate: (ExpertConnection) -> Unit,
    showPath: (ExpertConnection) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val streamsHeight = (maxHeight * 1.65f).coerceAtLeast(620.dp)
        ExpertScrollableColumn(Modifier.fillMaxSize()) {
            TunControl(state, onIntent, confirm)
            ExpertLiveStreams(state, Modifier.height(streamsHeight), simulate, showPath)
        }
    }
}

@Composable
private fun TunControl(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, confirm: (ExpertIntent) -> Unit) {
    val running = state.phase in setOf(ExpertPhase.RUNNING, ExpertPhase.APPLYING)
    val changing = state.phase in setOf(ExpertPhase.STARTING, ExpertPhase.STOPPING, ExpertPhase.APPLYING)
    val accent by animateColorAsState(
        when (state.tunnelHealth) {
            ExpertTunnelHealth.HEALTHY -> ExpertColors.green
            ExpertTunnelHealth.ERROR -> MaterialTheme.colorScheme.error
            ExpertTunnelHealth.PENDING -> ExpertColors.amber
            ExpertTunnelHealth.OFF -> ExpertColors.muted
        },
        tween(if (state.reducedMotion) 0 else 180), label = "tun-control",
    )
    Surface(color = ExpertColors.panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, accent.copy(alpha = .35f))) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            NetworkLever(
                checked = state.desiredEnabled,
                onCheckedChange = { enable -> if (enable) onIntent(ExpertIntent.Start) else confirm(ExpertIntent.Stop) },
                enabled = !state.busy && !changing,
                accessibleName = "Постоянный туннель: ${phaseName(state.phase)}",
                lamp = when (state.tunnelHealth) {
                    ExpertTunnelHealth.OFF -> NetworkLeverLamp.OFF
                    ExpertTunnelHealth.PENDING -> NetworkLeverLamp.PENDING
                    ExpertTunnelHealth.HEALTHY -> NetworkLeverLamp.HEALTHY
                    ExpertTunnelHealth.ERROR -> NetworkLeverLamp.ERROR
                },
                lampDescription = when (state.tunnelHealth) {
                    ExpertTunnelHealth.OFF -> "Лампа погашена: туннель выключен"
                    ExpertTunnelHealth.PENDING -> "Жёлтая лампа: операция ещё не завершена"
                    ExpertTunnelHealth.HEALTHY -> "Зелёная лампа: туннель работает, текущих сбоев каналов нет"
                    ExpertTunnelHealth.ERROR -> "Красная лампа: сбой туннеля или одного из наших каналов"
                },
                stateLabel = if (state.tunnelHealth == ExpertTunnelHealth.ERROR) {
                    "СБОЙ"
                } else {
                    when (state.phase) {
                        ExpertPhase.RUNNING -> "ВКЛ"
                        ExpertPhase.STOPPED -> "ВЫКЛ"
                        ExpertPhase.STARTING -> "ПУСК"
                        ExpertPhase.STOPPING -> "СТОП"
                        ExpertPhase.APPLYING -> "ПРИМЕНЕНИЕ"
                        ExpertPhase.FAILED -> "СБОЙ"
                    }
                },
                reducedMotion = state.reducedMotion,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Постоянный туннель", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        state.networkReason != null -> "Включён · ${state.networkReason}"
                        state.phase == ExpertPhase.STOPPED && state.protection.systemGuardEnforced ->
                            "Выключен · защита Windows продолжает блокировать обычный выход"
                        running -> if (state.hasDraftChanges || state.hasUnappliedChanges) "Сеть работает · есть неприменённые изменения" else "Соединения следуют схеме · схема применена"
                        state.phase == ExpertPhase.STOPPED -> "Выключен · включите, чтобы наблюдать и управлять соединениями"
                        else -> phaseName(state.phase)
                    },
                    color = accent, style = MaterialTheme.typography.bodyMedium
                )
                val policy = state.applied.takeIf { running } ?: state.saved
                Text(
                    "Основной путь: ${ExpertPolicyEditing.targetName(policy.device.defaultTarget, state)}",
                    style = MaterialTheme.typography.bodySmall, color = ExpertColors.muted
                )
                state.directNetwork?.takeIf { running }?.let { facts ->
                    Text(
                        "Системные маршруты Direct${facts.interfaceName?.let { " · основной интерфейс $it" }.orEmpty()}: " +
                            "IPv4 — ${familyMeaning(facts.ipv4)}; IPv6 — ${familyMeaning(facts.ipv6)}",
                        style = MaterialTheme.typography.bodySmall, color = ExpertColors.muted,
                    )
                    if (facts.ipv6 == DirectFamilyAvailability.UNAVAILABLE) {
                        Text(
                            "IPv6 напрямую недоступен. Возможности VPN-выходов проверяются отдельно.",
                            style = MaterialTheme.typography.bodySmall, color = ExpertColors.muted,
                        )
                    }
                }
            }
            when (state.phase) {
                ExpertPhase.STARTING -> OutlinedButton(onClick = { onIntent(ExpertIntent.Stop) }) { Text("Отменить запуск") }
                ExpertPhase.FAILED -> OutlinedButton(onClick = { confirm(ExpertIntent.Stop) }, enabled = !state.busy) {
                    Text("Завершить остановку")
                }
                else -> Unit
            }
        }
    }
}

private fun familyMeaning(value: DirectFamilyAvailability): String = when (value) {
    DirectFamilyAvailability.AVAILABLE -> "маршрут есть"
    DirectFamilyAvailability.LIMITED -> "есть отдельные маршруты"
    DirectFamilyAvailability.UNAVAILABLE -> "пути нет"
    DirectFamilyAvailability.UNKNOWN -> "недостаточно данных"
}

@Composable
internal fun ExpertLiveStreams(
    state: ExpertUiState,
    modifier: Modifier,
    simulate: (ExpertConnection) -> Unit,
    showPath: (ExpertConnection) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    var protectedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ExpertConnection?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasActive = state.connections.any { it.active == true }
    LaunchedEffect(hasActive) {
        now = System.currentTimeMillis()
        while (hasActive) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val sampler = remember(state.tunnelIdentity) { FlowRateSampler() }
    val rates = remember(state.connections, sampler) { sampler.update(state.connections) }
    val all = state.connections.filter { flow ->
        (!activeOnly || flow.active == true) &&
            (!protectedOnly || flow.protected) &&
            (
                query.isBlank() ||
                    listOf(flow.application, flow.destination, flow.domain.orEmpty(), flow.decision)
                        .any { it.contains(query.trim(), ignoreCase = true) }
                )
    }.sortedWith(
        compareByDescending<ExpertConnection> { it.startedAtMs ?: 0 }
            .thenByDescending { it.id.substringAfterLast(':').toLongOrNull() ?: 0L }
            .thenByDescending { it.id }
    )
    val long = all.filter { isLongLivedFlow(it, now) }
    val recent = all.filterNot { isLongLivedFlow(it, now) }
    val activeRates = state.connections.filter { it.active == true }
        .mapNotNull { freshFlowRate(it, rates[it.id], now) }
    val activeCount = state.connections.count { it.active == true }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Найти программу, сайт или адрес") }
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(activeOnly, { activeOnly = !activeOnly }, label = { Text("Активные") })
            FilterChip(protectedOnly, { protectedOnly = !protectedOnly }, label = { Text("С запретом прямого выхода") })
            Text(
                if (activeRates.isEmpty()) {
                    "Скорость ещё не измерена"
                } else {
                    (
                        if (activeRates.size < activeCount) {
                            "Скорость ${activeRates.size} из $activeCount соединений: "
                        } else {
                            "Наблюдаемый трафик: "
                        }
                        ) + "↑ ${formatTraffic(activeRates.sumOf { it.upload })}/с  " +
                        "↓ ${formatTraffic(activeRates.sumOf { it.download })}/с"
                },
                Modifier.align(Alignment.CenterVertically), color = ExpertColors.muted, fontSize = 12.sp,
            )
        }
        Text(
            if (state.connectionHistoryTruncated) {
                "Показана ограниченная часть истории · до ${state.connectionHistoryLimit} записей. " +
                    "Вытеснено: ${state.connectionDroppedCount}."
            } else {
                "История текущей сессии · до ${state.connectionHistoryLimit} соединений. " +
                    "HTTPS-запросы внутри соединения не раскрываются."
            },
            color = if (state.connectionHistoryTruncated) ExpertColors.amber else ExpertColors.muted, fontSize = 12.sp,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 740.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TrafficColumn("Недавние / разовые", recent, rates, state, now, Modifier.weight(1f)) { selected = it }
                    TrafficColumn("Длительные · от 30 с", long, rates, state, now, Modifier.weight(1f)) { selected = it }
                }
            } else {
                var longTab by rememberSaveable { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!longTab, { longTab = false }, label = { Text("Недавние · ${recent.size}") })
                        FilterChip(longTab, { longTab = true }, label = { Text("Длительные · ${long.size}") })
                    }
                    TrafficColumn(
                        if (longTab) "Длительные · от 30 с" else "Недавние / разовые",
                        if (longTab) long else recent, rates, state, now, Modifier.weight(1f)
                    ) { selected = it }
                }
            }
        }
    }
    selected?.let { captured ->
        val current = state.connections.firstOrNull { it.id == captured.id }
            ?: captured.copy(active = captured.active.takeIf { it == false })
        ExpertConnectionDetails(current, state, now, { selected = null }, {
            selected = null
            simulate(current)
        }, {
            selected = null
            showPath(current)
        })
    }
}

@Composable
private fun TrafficColumn(
    title: String,
    flows: List<ExpertConnection>,
    rates: Map<String, FlowRate>,
    state: ExpertUiState,
    now: Long,
    modifier: Modifier,
    select: (ExpertConnection) -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var live by rememberSaveable(state.tunnelIdentity, title) { mutableStateOf(true) }
    LaunchedEffect(list.interactionSource) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) live = false }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) }
            .collect { moved -> if (moved) live = false }
    }
    LaunchedEffect(live, flows.firstOrNull()?.id) {
        if (live) list.scrollToItem(0)
    }
    Surface(modifier, color = ExpertColors.panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, ExpertColors.border)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(flows.size.toString(), color = ExpertColors.muted)
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
            HorizontalDivider(color = ExpertColors.border)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(end = 14.dp), state = list,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (flows.isEmpty()) {
                        item {
                            Text(
                                if (state.phase == ExpertPhase.STOPPED) {
                                    "Включите туннель для наблюдения"
                                } else {
                                    "Подходящих соединений пока нет"
                                },
                                color = ExpertColors.muted, fontSize = 13.sp
                            )
                        }
                    }
                    items(flows, key = { it.id }) { flow ->
                        TrafficRow(flow, state, freshFlowRate(flow, rates[flow.id], now), now) { select(flow) }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            Text(
                if (live) "Новые соединения сверху · слежение включено" else "Слежение приостановлено · Live вернёт к новым",
                color = ExpertColors.muted, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TrafficRow(flow: ExpertConnection, state: ExpertUiState, rate: FlowRate?, now: Long, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val application = state.applications.firstOrNull { it.executable.equals(flow.processPath, ignoreCase = true) }
            application?.icon?.let { Image(it, null, Modifier.size(22.dp)) }
            Text(
                flow.application, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium, fontSize = 14.sp
            )
            Text(
                when (flow.active) {
                    true -> "● активно"
                    false -> "завершено"
                    null -> "неизвестно"
                },
                color = if (flow.active == true) ExpertColors.green else ExpertColors.muted, fontSize = 11.sp
            )
        }
        Text(flow.destination, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        Text(
            "${flow.network ?: flow.protocol.ifBlank { "Транспорт неизвестен" }} · ${flowDuration(flow, now)}",
            color = ExpertColors.muted, fontSize = 12.sp
        )
        Text(
            if (rate == null) {
                "↑ ${formatTraffic(flow.uploadedBytes)}  ↓ ${formatTraffic(flow.downloadedBytes)}"
            } else {
                "↑ ${formatTraffic(rate.upload)}/с  ↓ ${formatTraffic(rate.download)}/с"
            },
            color = ExpertColors.muted, fontSize = 12.sp
        )
        flow.errorReason?.let { Text(flowErrorMeaning(it), color = ExpertColors.red, fontSize = 12.sp, maxLines = 2) }
    }
}

@Composable
private fun ExpertConnectionDetails(
    flow: ExpertConnection,
    state: ExpertUiState,
    now: Long,
    close: () -> Unit,
    simulate: () -> Unit,
    showPath: () -> Unit,
) {
    var content by rememberSaveable(flow.id) { mutableStateOf(false) }
    ExpertModal("Соединение", close, close, true, "Закрыть", showCancel = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!content, { content = false }, label = { Text("Сведения") })
            FilterChip(content, { content = true }, label = { Text("Данные и передачи") })
        }
        if (content) {
            FlowContent(flow.inspection, flow.network, flow.sniffedProtocol)
        } else {
            Reading("Программа", flow.application)
            Reading("Назначение", flow.destination)
            Reading("Домен", flow.domain ?: "Не передан; HTTPS не расшифровывается")
            Reading("Адрес / порт", flow.destinationIp?.let { "$it · порт ${flow.destinationPort ?: "неизвестен"}" } ?: "Не переданы")
            Reading("Источник", flow.sourceIp?.let { "$it · порт ${flow.sourcePort ?: "неизвестен"}" } ?: "Не передан")
            Reading(
                "Транспорт / распознанный протокол",
                "${flow.network ?: flow.protocol.ifBlank { "Неизвестен" }} / ${flow.sniffedProtocol ?: "не определён"}"
            )
            Reading(
                "Состояние",
                when (flow.active) {
                    true -> "Активно"
                    false -> "Завершено"
                    null -> "Не передано"
                }
            )
            Reading("Длительность", flowDuration(flow, now))
            flow.closeReason?.let {
                Reading(
                    "Завершение",
                    when (it) {
                        "finished" -> "Соединение завершено"
                        "idle_timeout" -> "Истёк срок ожидания активности"
                        "reset" -> "Соединение сброшено"
                        else -> it
                    }
                )
            }
            Reading("Передано", "↑ ${formatTraffic(flow.uploadedBytes)}  ↓ ${formatTraffic(flow.downloadedBytes)}")
            Reading("Решение", flow.decision.ifBlank { "Не передано" })
            Reading("Версия схемы", flow.policyRevision?.toString() ?: "Не передана")
            Reading(
                "Выход LerNET",
                flow.exitId?.let { id -> state.exits.firstOrNull { it.id == id }?.name ?: id }
                    ?: "Отдельный выход не указан; решение показано выше"
            )
            flow.errorReason?.let {
                ExpertMessage(
                    "Причина отказа",
                    "${flow.errorStage?.let { stage -> flowStageMeaning(stage) + ": " }.orEmpty()}" +
                        "${flowErrorMeaning(it)} ($it)",
                    error = true
                )
            }
            Text(flow.explanation, color = ExpertColors.muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = simulate) { Text("В симулятор") }
                TextButton(onClick = showPath) { Text("Показать путь") }
            }
        }
    }
}

@Composable
private fun Reading(label: String, value: String) {
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = ExpertColors.muted, fontSize = 12.sp)
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun FlowContent(inspection: FlowInspection?, network: String?, protocol: String?) {
    var upload by rememberSaveable { mutableStateOf(true) }
    var hex by rememberSaveable { mutableStateOf(false) }
    if (inspection == null) {
        ExpertMessage("Данных пока нет", "В этом соединении ещё не наблюдались передачи, либо ядро не поддерживает просмотр.")
        return
    }
    val encrypted = inspection.encrypted(protocol)
    if (!inspection.payloadAvailable) {
        ExpertMessage("Только счётчики", "Ядро передало объёмы, но не содержимое этого потока. Границы пакетов неизвестны.")
    }
    ExpertMessage(
        if (encrypted) "Защищено шифрованием" else "Наблюдаемые байты",
        if (encrypted) {
            "TLS/QUIC: тело запросов скрыто. Ниже — исходные байты, а не расшифрованные сообщения. " +
                "Для расшифровки нужны ключи приложения или отдельный доверенный TLS-прокси."
        } else {
            "Первые ${FlowInspection.PREFIX_LIMIT} байт в каждом направлении. Это фрагмент, а не полная запись запроса. " +
                "Текст может содержать заголовки и начало тела открытого протокола. Бинарные данные смотрите в HEX."
        },
    )
    Text(
        "Просмотр хранится в памяти текущей сессии. В обычный журнал содержимое не записывается.",
        fontSize = 12.sp, color = ExpertColors.muted
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(upload, { upload = true }, label = { Text("↑ От программы") })
        FilterChip(!upload, { upload = false }, label = { Text("↓ К программе") })
        FilterChip(hex, { hex = !hex }, label = { Text("HEX") })
    }
    val bytes = inspection.bytes(upload)
    Text("Начало потока · ${bytes.size}/${FlowInspection.PREFIX_LIMIT} байт", fontSize = 12.sp, color = ExpertColors.muted)
    Surface(color = ExpertColors.background, shape = RoundedCornerShape(10.dp)) {
        SelectionContainer {
            Text(
                if (bytes.isEmpty()) {
                    "Содержимое не наблюдалось"
                } else if (hex ||
                    encrypted
                ) {
                    inspection.hex(upload)
                } else {
                    inspection.text(upload)
                },
                Modifier.fillMaxWidth().padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp
            )
        }
    }
    Text("Последние передачи · ${inspection.transfers.size} из ${inspection.transferCount}", fontWeight = FontWeight.SemiBold)
    Text(
        if (!inspection.payloadAvailable) {
            "События счётчика ядра; границы пакетов неизвестны."
        } else if (network == "udp") {
            "Каждая строка — наблюдаемая UDP-датаграмма."
        } else {
            "Каждая строка — чтение или запись TCP-потока; границы пакетов и сообщений здесь не сохраняются."
        },
        fontSize = 12.sp, color = ExpertColors.muted
    )
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()) }
    inspection.transfers.forEach { transfer ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time.format(Instant.ofEpochMilli(transfer.atMs)), fontSize = 12.sp, color = ExpertColors.muted)
            Text(if (transfer.upload) "↑ от программы" else "↓ к программе", fontSize = 12.sp)
            Text(formatTraffic(transfer.bytes), fontSize = 12.sp)
        }
    }
}
