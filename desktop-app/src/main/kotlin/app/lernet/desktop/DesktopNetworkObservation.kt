package app.lernet.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.engine.net.observation.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val observationMint = Color(0xFF80DEBE)
private val observationAmber = Color(0xFFF1CC80)
private val observationInk = Color(0xFF101824)

private enum class ObservationPage(val title: String, val mark: String, val help: String) {
    OVERVIEW("Обзор", "◎", "Исследуйте настройки, по которым Windows выбирает путь трафика."),
    ADAPTERS("Адаптеры", "▤", "Физические и виртуальные входы в сеть. Включённый адаптер ещё не означает работающий VPN."),
    APPS("Программы и VPN", "◈", "Кто использует сеть и какие подключения зарегистрированы в Windows."),
    ROUTES("Маршруты", "⑂", "Для каждого адреса Windows выбирает наиболее точный маршрут, затем сравнивает метрики."),
    DNS("DNS и прокси", "◇", "Кто переводит имена сайтов в адреса и куда приложения отправляют запросы."),
    FILTERS("Фильтры", "▧", "Правила, которые могут разрешить или остановить трафик внутри компьютера."),
    SYSTEM("Устройство", "⌘", "Драйверы, привязки, беспроводная сеть и дополнительные сетевые компоненты."),
    EVENTS("События", "≋", "Существующие события Windows и короткая запись для разбора конкретного сбоя."),
    CHANGES("Что изменилось", "⇄", "Сравнение двух последних снимков. Изменение само по себе не указывает на виновника."),
    ALL("Все источники", "⋮", "Данные системы с указанием источника и времени чтения."),
}

/** A read-only view: all operating-system work belongs to the caller. */
@Composable
internal fun DesktopNetworkObservation(
    snapshot: NetworkSnapshot?, previous: NetworkSnapshot?, busy: Boolean, error: String?,
    onRefresh: () -> Unit, onExport: () -> Unit, onProbe: () -> Unit, probeResult: String?,
    onStartTrace: () -> Unit, onStopTrace: () -> Unit, traceRunning: Boolean, traceStatus: String,
    onCancel: (() -> Unit)? = null,
    comparisonSnapshot: NetworkSnapshot? = snapshot,
) {
    var page by remember { mutableStateOf(ObservationPage.OVERVIEW) }
    val sectionListState = remember(page) { LazyListState() }
    var query by remember { mutableStateOf("") }
    var reducedMotion by remember { mutableStateOf(false) }
    var confirmProbe by remember { mutableStateOf(false) }
    var findingFilter by remember { mutableStateOf<FindingKind?>(null) }
    var confirmTrace by remember { mutableStateOf(false) }
    val sources = snapshot?.sources.orEmpty()
    val changes = remember(previous, comparisonSnapshot) {
        if (previous != null && comparisonSnapshot != null) NetworkObservationAnalysis.compare(previous, comparisonSnapshot) else emptyList()
    }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val compact = maxWidth < 850.dp
        Row(Modifier.fillMaxSize()) {
            if (!compact) Column(Modifier.width(214.dp).fillMaxHeight().background(observationInk).padding(16.dp)) {
                Text("LerNET", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Сеть устройства", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Spacer(Modifier.height(26.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ObservationPage.entries.forEach { item ->
                    val count = if (item == ObservationPage.CHANGES) changes.size else sources.count { sourceMatches(item, it.id) }
                    val fill by animateColorAsState(if (page == item) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        tween(if (reducedMotion) 0 else 160))
                    Surface(onClick = { page = item; query = "" }, color = fill, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("network-nav-${item.name}")
                            .semantics { selected = page == item }) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.mark, color = if (page == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 18.sp, modifier = Modifier.width(27.dp))
                            Text(item.title, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (count > 0 && item != ObservationPage.OVERVIEW) Text(count.toString(), fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Меньше движения", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(reducedMotion, { reducedMotion = it }, Modifier.padding(start = 4.dp).semantics { contentDescription = "Меньше движения" })
                }
                Text("Наблюдение не меняет настройки сети.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(if (compact) 16.dp else 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Сеть устройства", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (snapshot == null) "Посмотрим, как устроено подключение" else
                            "${snapshot.platform} · снимок ${observationTime(snapshot.finishedAt)} · ${if (snapshot.elevated) "права администратора" else "обычные права"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    if (!compact) OutlinedButton(onExport, enabled = snapshot != null && !busy) { Text("Сохранить отчёт") }
                    if (busy && onCancel != null) TextButton(onCancel) { Text("Отменить") }
                    Button(onRefresh, enabled = !busy) { Text(if (busy) "Читаем…" else if (snapshot == null) "Исследовать" else "Обновить") }
                }
                Spacer(Modifier.height(18.dp))
                if (compact) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ObservationPage.entries.forEach { item -> FilterChip(page == item, { page = item; query = "" }, { Text(item.title) },
                            modifier = Modifier.testTag("network-nav-${item.name}")) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onExport, enabled = snapshot != null && !busy) { Text("Сохранить отчёт") }
                        Spacer(Modifier.weight(1f))
                        Text("Меньше движения", fontSize = 11.sp)
                        Switch(reducedMotion, { reducedMotion = it }, Modifier.semantics { contentDescription = "Меньше движения" })
                    }
                }
                if (busy) {
                    if (reducedMotion) LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Читаем доступные источники Windows. Предыдущий снимок остаётся видимым.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!error.isNullOrBlank()) Notice("Действие не завершилось", error, MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(page.help, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
                if (snapshot == null) {
                    EmptyObservation(busy)
                } else when (page) {
                    ObservationPage.OVERVIEW -> LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { NetworkSettingsExplorer(snapshot, compact, reducedMotion, onPage = { page = it }) }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Внешний адрес", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                OutlinedButton({ confirmProbe = true }, enabled = !busy) { Text("Узнать мой IP") }
                            }
                            Text("Адрес снаружи виден только внешнему сервису. Проверка запускается отдельно от снимка.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            if (!probeResult.isNullOrBlank()) SelectionContainer { Text(probeResult, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Что стоит заметить", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("${snapshot.findings.size} наблюдений", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (snapshot.findings.isEmpty()) item { Notice("Из доступных данных конфликт не найден",
                            "Это не проверка работоспособности VPN. Маршрут и ответ сервера проверяются отдельно.", observationMint) }
                        item { FindingFilters(snapshot.findings, findingFilter) { findingFilter = it } }
                        items(snapshot.findings.filter { findingFilter == null || it.kind == findingFilter }, key = { it.code }) { finding -> FindingDetail(ObservationFindingGuide.explain(snapshot, finding), reducedMotion, sources.associate { it.id to it.title }) { sourceId ->
                            page = ObservationPage.ALL; query = sourceId
                        } }
                        item {
                            val missing = sources.count { it.state !in setOf(SourceState.AVAILABLE, SourceState.EMPTY) }
                            Text("Доступно ${sources.size - missing} из ${sources.size} источников · сбор ${((snapshot.finishedAt - snapshot.startedAt).coerceAtLeast(0)) / 1000.0} с",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Снимок описывает настройки на момент чтения. Он не доказывает, что пакеты дошли до интернета.", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    ObservationPage.CHANGES -> {
                        if (previous == null) Notice("Нужен второй снимок", "Нажмите «Обновить» после изменения сети. Здесь появится сравнение с предыдущим снимком.")
                        else if (changes.isEmpty()) Notice("Настройки совпадают", "В прочитанных источниках изменений не найдено. Состояние самого канала могло измениться без изменения настроек.", observationMint)
                        else LazyColumn(state = sectionListState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { PageReadingGuide(page.title, pageReadingPlan(page), reducedMotion) }
                            item { Text("${observationTime(previous.finishedAt)} → ${observationTime(snapshot.finishedAt)} · ${changes.size} изменений",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(changes) { change -> ChangeDetail(change) }
                        }
                    }
                    else -> {
                        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Найти в этом разделе") },
                            modifier = Modifier.fillMaxWidth(), trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }) { Text("Сбросить") } })
                        Spacer(Modifier.height(12.dp))
                        val selected = sources.filter { sourceMatches(page, it.id) }.filter { source ->
                            query.isBlank() || source.id.contains(query, true) || source.title.contains(query, true) ||
                                source.rows.any { it.title.contains(query, true) || it.fields.any { (k, v) -> k.contains(query, true) || v.contains(query, true) } }
                        }
                        if (selected.isEmpty()) Notice(if (query.isBlank()) "Нет источников в этом разделе" else "Совпадений не найдено",
                            if (query.isBlank()) "Посмотрите «Все источники»: доступность зависит от версии Windows и прав." else "Попробуйте имя адаптера, адрес, процесс или название правила.")
                        else LazyColumn(state = sectionListState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { PageReadingGuide(page.title, pageReadingPlan(page), reducedMotion) }
                            if (page == ObservationPage.ROUTES) item { RoutePlayground(snapshot) }
                            if (page == ObservationPage.EVENTS) item { TraceControls(traceRunning, traceStatus, onStart = { confirmTrace = true }, onStopTrace) }
                            items(selected, key = { it.id }) { source -> SourceDetail(source, query, reducedMotion, snapshot) }
                        }
                    }
                }
            }
        }
    }
    }
    if (confirmProbe) AlertDialog(onDismissRequest = { confirmProbe = false }, title = { Text("Проверить внешний IP?") },
        text = { Text("Будет выполнен отдельный HTTPS-запрос к api.ipify.org через текущие настройки сети. Этот сервис увидит адрес выхода. Список программ, адаптеров и отчёт ему не отправляются. Проверка не обходит VPN.") },
        confirmButton = { Button({ confirmProbe = false; onProbe() }) { Text("Проверить") } },
        dismissButton = { TextButton({ confirmProbe = false }) { Text("Отмена") } })
    if (confirmTrace) AlertDialog(onDismissRequest = { confirmTrace = false }, title = { Text("Разобрать сбой") },
        text = { Text("Запишем системные события TCP/IP в течение 45 секунд, до 32 МБ на устройстве. Запись общая для компьютера, а не только для выбранной программы. После старта воспроизведите сбой обычным способом. Эта кнопка не включает VPN. Содержимое пакетов и расшифровка HTTPS не запрашиваются. Данные останутся на устройстве.") },
        confirmButton = { Button({ confirmTrace = false; onStartTrace() }) { Text("Начать запись") } },
        dismissButton = { TextButton({ confirmTrace = false }) { Text("Отмена") } })
}

@Composable
private fun EmptyObservation(busy: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("◎", color = MaterialTheme.colorScheme.primary, fontSize = 54.sp)
        Text(if (busy) "Знакомимся с вашей сетью" else "У каждого подключения есть история", style = MaterialTheme.typography.titleLarge)
        Text("Адаптеры, маршруты, DNS и правила Windows — в одном месте.\nНачните со снимка; никаких изменений сети он не делает.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun RoutePlayground(snapshot: NetworkSnapshot) {
    var address by remember { mutableStateOf("") }
    var inspected by remember { mutableStateOf<String?>(null) }
    val result = remember(snapshot, inspected) { inspected?.let { NetworkRouteSelection.select(snapshot, it) } }
    Surface(color = observationInk, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag("network-route-playground")) {
        Column(Modifier.padding(16.dp)) {
            Text("Куда пошёл бы этот адрес?", fontWeight = FontWeight.SemiBold)
            Text("Рассчитаем по снимку, без DNS и отправки пакетов. Сетевой контекст: основной (1).", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(address, { address = it; inspected = null }, singleLine = true, label = { Text("Числовой IPv4 или IPv6") },
                    placeholder = { Text("Например, 1.1.1.1") }, modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.Enter, Key.NumPadEnter) && address.isNotBlank()) {
                            inspected = address.trim(); true
                        } else false
                    })
                Button({ inspected = address.trim() }, enabled = address.isNotBlank()) { Text("Показать путь") }
            }
            if (result != null) {
                if (!result.error.isNullOrBlank()) Text(result.error.orEmpty(), fontSize = 12.sp, color = observationAmber, modifier = Modifier.padding(top = 12.dp))
                else {
                    Text(if (result.candidates.size > 1) "Несколько равных кандидатов; выбор Windows по снимку не определён." else
                        "Кандидат по наиболее точному префиксу и сумме метрик:", color = observationMint, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    result.candidates.forEach { route ->
                        val adapter = snapshot.adapters.firstOrNull { it.id == route.adapterId }
                        SelectionContainer { Text("${route.prefix} → ${adapter?.name ?: "интерфейс ${route.interfaceIndex}"} → ${route.nextHop}\n" +
                            "Метрика ${route.metric} + ${route.interfaceMetric} = ${route.metric.toLong() + route.interfaceMetric.toLong()}",
                            fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                    }
                    Text("Это расчёт настроек, а не подтверждение доступности адреса.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun NetworkSettingsExplorer(snapshot: NetworkSnapshot, compact: Boolean, reducedMotion: Boolean, onPage: (ObservationPage) -> Unit) {
    var selected by remember { mutableStateOf(3) }
    val titles = listOf("Программы", "DNS и прокси", "Маршруты", "Адаптеры")
    val marks = listOf("◈", "◇", "⑂", "▤")
    val targets = listOf(ObservationPage.APPS, ObservationPage.DNS, ObservationPage.ROUTES, ObservationPage.ADAPTERS)
    val summaries = listOf("Приложение выбирает прямое соединение или настроенный прокси.",
        "DNS определяет адрес назначения; прокси может изменить путь запроса приложения.",
        "Найдено ${snapshot.routes.size} маршрутов. Точный префикс важнее низкой метрики общего маршрута.",
        "${snapshot.adapters.count { it.up }} из ${snapshot.adapters.size} адаптеров включены. Маршрут определяет, какой из них используется для нужного адреса.")
    val focus by animateFloatAsState(selected.toFloat(), tween(if (reducedMotion) 0 else 220))
    Surface(color = observationInk, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Как Windows выбирает путь", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("Схема настроек", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Нажмите на участок, чтобы понять его роль", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Box(Modifier.fillMaxWidth().height(if (compact) 126.dp else 142.dp)) {
                val wire = MaterialTheme.colorScheme.outlineVariant
                val accent = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val step = size.width / 4
                    val centerY = size.height / 2 - 13.dp.toPx()
                    drawLine(wire, Offset(step / 2, centerY), Offset(size.width - step / 2, centerY), strokeWidth = 2.dp.toPx())
                    drawCircle(accent.copy(alpha = .15f), radius = 37.dp.toPx(), center = Offset(step * (focus + .5f), centerY))
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    titles.forEachIndexed { index, title ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Surface(onClick = { selected = index }, shape = RoundedCornerShape(15.dp),
                                color = if (selected == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.size(if (compact) 48.dp else 58.dp).semantics {
                                    contentDescription = title
                                    stateDescription = if (selected == index) "Выбранный участок" else "Исследовать участок"
                                }) {
                                Box(contentAlignment = Alignment.Center) { Text(marks[index], fontSize = 24.sp, color = MaterialTheme.colorScheme.primary) }
                            }
                            Text(title, fontSize = if (compact) 11.sp else 13.sp, modifier = Modifier.padding(top = 9.dp), maxLines = 1)
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(titles[selected], color = observationMint, fontWeight = FontWeight.SemiBold)
                    Text(summaries[selected], fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                }
                TextButton({ onPage(targets[selected]) }) { Text("Исследовать →") }
            }
            if (selected == 3 && snapshot.adapters.isNotEmpty()) Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                snapshot.adapters.forEach { adapter -> SuggestionChip({ onPage(ObservationPage.ADAPTERS) }, {
                    Text("${if (adapter.up) "●" else "○"} ${adapter.name}", color = if (adapter.up) observationMint else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }) }
            }
        }
    }
}

@Composable
private fun FindingDetail(finding: NetworkFinding, reducedMotion: Boolean, sourceTitles: Map<String, String>, onSource: (String) -> Unit) {
    var expanded by remember(finding.code) { mutableStateOf(false) }
    val tint = when (finding.kind) {
        FindingKind.FACT -> observationMint
        FindingKind.POTENTIAL_CONFLICT -> observationAmber
        FindingKind.INSUFFICIENT_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (finding.kind) { FindingKind.FACT -> "Факт"; FindingKind.POTENTIAL_CONFLICT -> "Возможный конфликт"; FindingKind.INSUFFICIENT_DATA -> "Не хватает данных" }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, tint.copy(alpha = .28f))) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { expanded = !expanded }
                .testTag("network-finding-${finding.code}").semantics { stateDescription = if (expanded) "Доказательства раскрыты" else "Показать доказательства" }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(tint, CircleShape))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(label, color = tint, fontSize = 11.sp)
                    Text(finding.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
                }
                Text(if (expanded) "−" else "+", color = tint, fontSize = 22.sp)
            }
            Text(finding.explanation, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (finding.relatedItems.isNotEmpty()) Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                finding.relatedItems.take(3).forEach { item ->
                    Surface(color = tint.copy(alpha = .08f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, tint.copy(alpha = .2f))) {
                        Text(item.title, color = tint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                if (finding.relatedItems.size > 3) Text("+${finding.relatedItems.size - 3}", color = tint, modifier = Modifier.padding(6.dp), fontSize = 11.sp)
            }
            ReadingDisclosure(expanded, reducedMotion) { FindingEvidence(finding, sourceTitles, onSource) }
        }
    }
}

@Composable
private fun FindingEvidence(finding: NetworkFinding, sourceTitles: Map<String, String>, onSource: (String) -> Unit) {
    var count by remember(finding.code) { mutableStateOf(4) }
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        ReadingBlock("Как мы это определили", finding.reason)
        ReadingBlock("На что это может повлиять", finding.impact)
        ReadingSteps(finding.nextSteps)
        if (finding.relatedItems.isNotEmpty()) {
            Text("Конкретные объекты · ${finding.relatedItems.size}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            finding.relatedItems.take(count).forEach { item ->
                Surface(modifier = Modifier.fillMaxWidth(), color = observationInk, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            item.fields.forEach { (key, value) ->
                                Text(key, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                Text(value, fontSize = 12.sp)
                            }
                        } }
                    }
                }
            }
            if (count < finding.relatedItems.size) TextButton({ count += 8 }) { Text("Показать ещё ${minOf(8, finding.relatedItems.size - count)} объектов") }
        } else if (finding.evidence.isNotEmpty()) {
            Text("Основания из снимка", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { finding.evidence.take(count).forEach { Text(it, fontSize = 12.sp) } } }
            if (count < finding.evidence.size) TextButton({ count += 8 }) { Text("Показать ещё основания") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            finding.sourceIds.forEach { source -> TextButton({ onSource(source) }) { Text("Открыть: ${sourceTitles[source] ?: source}", fontSize = 11.sp) } }
        }
    }
}

@Composable
private fun SourceDetail(source: ObservationSource, query: String, reducedMotion: Boolean, snapshot: NetworkSnapshot) {
    var expanded by remember(source.id) { mutableStateOf(false) }
    var displayCount by remember(source.id, query) { mutableStateOf(12) }
    val stateColor = when (source.state) { SourceState.AVAILABLE -> observationMint; SourceState.EMPTY, SourceState.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant; else -> observationAmber }
    val rows = remember(source, query) {
        if (query.isBlank() || source.id.contains(query, true) || source.title.contains(query, true)) source.rows
        else source.rows.filter { it.title.contains(query, true) || it.fields.any { (k, v) -> k.contains(query, true) || v.contains(query, true) } }
    }
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(top = 12.dp)) {
            SourceReadingGuide(source, reducedMotion)
            Spacer(Modifier.height(12.dp))
            if (rows.isEmpty()) Text(if (source.state == SourceState.EMPTY) "Источник прочитан; записей нет." else "Нет записей для отображения.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            rows.take(displayCount).forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                EvidenceDetail(row, source.id, snapshot, reducedMotion)
            }
            if (rows.size > displayCount) TextButton({ displayCount += 12 }) { Text("Ещё ${minOf(12, rows.size - displayCount)} из ${rows.size - displayCount}") }
        }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag("network-source-${source.id}").clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Источник раскрыт" else "Показать записи источника" }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.title, fontWeight = FontWeight.SemiBold)
                    Text("${sourceStateLabel(source.state)}${if (!source.complete) " · неполная выборка" else ""} · ${source.rows.size} записей · ${observationTime(source.capturedAt)}", color = stateColor, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Text(if (expanded) "−" else "+", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            }
            Text(ObservationGuide.source(source.id).meaning, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            ReadingDisclosure(expanded || query.isNotBlank(), reducedMotion) { body() }
        }
    }
}

@Composable
private fun EvidenceDetail(row: EvidenceRow, sourceId: String, snapshot: NetworkSnapshot, reducedMotion: Boolean) {
    var expanded by remember(row.id) { mutableStateOf(false) }
    val reading = remember(sourceId, row, snapshot) { ObservationGuide.row(sourceId, row, snapshot) }
    val executable = row.fields["ExecutablePath"].orEmpty()
    val identity by produceState<ProcessIdentity?>(null, executable) {
        value = null
        if (executable.isNotBlank()) value = withContext(Dispatchers.IO) {
            runCatching { WindowsProcessIdentity.resolveObservation(executable) }.getOrNull()
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable(role = Role.Button) { expanded = !expanded }
            .testTag("network-row-${row.id}")
            .semantics { stateDescription = if (expanded) "Пояснения раскрыты" else "Показать пояснения" }
            .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (executable.isNotBlank()) {
                val icon = identity?.icon
                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                else ReadingMark("◈", MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
            }
            Text(reading.summary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            Text(if (expanded) "Свернуть ↑" else "Разобраться ↓", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
        }
        ReadingDisclosure(expanded, reducedMotion) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingBlock("Что означает эта запись", reading.interpretation)
                ExplainedFields(row.fields)
            }
        }
    }
}

@Composable
private fun ChangeDetail(change: SnapshotChange) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(change.title, fontWeight = FontWeight.SemiBold)
            Text(ObservationGuide.source(change.sourceId).meaning, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            ReadingBlock("Как понимать изменение", "Мы сравнили два снимка одного источника. Это изменение настройки или доступности данных; оно не показывает, кто его сделал и стало ли соединение работать.")
            SelectionContainer { Column(Modifier.padding(top = 12.dp)) {
                Text("Было", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(change.before, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                Text("Стало", fontSize = 11.sp, color = observationMint)
                Text(change.after, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            } }
        }
    }
}

@Composable
private fun TraceControls(running: Boolean, status: String, onStart: () -> Unit, onStop: () -> Unit) {
    Surface(color = observationInk, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (running) observationMint else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (running) "Записываем события" else "Разобрать конкретный сбой", fontWeight = FontWeight.SemiBold)
                    Text(if (running) "Воспроизведите проблему; остановится только наша запись." else "Короткий локальный сеанс. VPN включаете отдельно.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                if (running) OutlinedButton(onStop) { Text("Остановить") } else Button(onStart) { Text("Разобрать сбой") }
            }
            if (status.isNotBlank()) SelectionContainer { Text(status, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun Notice(title: String, text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, color = tint, fontWeight = FontWeight.SemiBold)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun sourceMatches(page: ObservationPage, id: String): Boolean {
    val key = id.lowercase(Locale.ROOT)
    return when (page) {
        ObservationPage.OVERVIEW, ObservationPage.ALL -> true
        ObservationPage.ADAPTERS -> key in setOf("adapters", "addresses", "interfaces", "network-profiles")
        ObservationPage.APPS -> key.contains("process") || key.contains("apps") || key.contains("vpn") || key.contains("listener") || key.contains("connection") || key.contains("service") || key in setOf("udp-endpoints", "lernet-context")
        ObservationPage.ROUTES -> key.contains("route")
        ObservationPage.DNS -> key.contains("dns") || key.contains("proxy") || key.contains("hosts") || key.contains("nrpt")
        ObservationPage.FILTERS -> key.contains("firewall") || key.contains("wfp") || key.contains("filter")
        ObservationPage.SYSTEM -> key in setOf("bindings", "drivers", "adapter-properties", "adapter-counters", "neighbors", "compartments", "ipsec", "nat", "nat-mappings", "hyperv-switch", "winsock", "wlan") || key.contains("system")
        ObservationPage.EVENTS -> key.contains("event") || key.contains("trace")
        ObservationPage.CHANGES -> false
    }
}

private fun pageReadingPlan(page: ObservationPage): List<String> = when (page) {
    ObservationPage.ADAPTERS -> listOf("Найдите Wi-Fi, кабель или туннель, который должен обслуживать ваш запрос.", "Сопоставьте состояние и адреса с маршрутом нужного ресурса.", "Виртуальный интерфейс и WAN Miniport сами по себе не означают подключённый VPN.")
    ObservationPage.APPS -> listOf("Начните с нужной программы и её пути EXE.", "Сопоставьте PID с родителем, службой, портами и соединениями.", "Установленный профиль, запущенная служба и работающий туннель — разные факты.")
    ObservationPage.ROUTES -> listOf("Введите IP проблемного ресурса в расчёт пути.", "Посмотрите найденный адаптер, префикс и следующий узел; служебные маршруты смотрите отдельно от интернет-пути.", "Расчёт по таблице не подтверждает ответ сервера и не учитывает собственный прокси приложения.")
    ObservationPage.DNS -> listOf("Если имя не открывается, проверьте DNS используемого интерфейса.", "Если приложения ведут себя по-разному, сравните пользовательский прокси, WinHTTP и окружение.", "Локальный прокси должен иметь работающий порт; внутреннему DNS нужен доступный путь.")
    ObservationPage.FILTERS -> listOf("Начните с категории сети и действия брандмауэра по умолчанию.", "Для правила сопоставьте программу, направление, адреса, порты и протокол одновременно.", "Подтверждение блокировки ищите в событии нужного процесса в момент сбоя; отсутствие в выборке не означает отсутствие фильтра.")
    ObservationPage.SYSTEM -> listOf("Исследуйте компоненты, связанные с используемым адаптером.", "Сравните драйверы, привязки и счётчики до и после сбоя.", "Отсутствие необязательного Hyper-V/NAT и наличие штатных компонентов — не повод менять настройки.")
    ObservationPage.EVENTS -> listOf("Сопоставьте время события со временем проблемы.", "Ищите нужную программу и адрес, затем сопоставьте номер фильтра с WFP.", "Если журнал не прочитан или отключён, отсутствие событий не доказывает исправность соединения.")
    else -> listOf("Начните с наблюдения, относящегося к вашему сбою.", "Раскройте источник: рядом с каждой записью есть объяснение, поля и их смысл.", "Сравнивайте два снимка. Факт изменения не устанавливает виновника и не подтверждает работу сети.")
}

private fun sourceStateLabel(state: SourceState): String = when (state) {
    SourceState.AVAILABLE -> "Прочитан"
    SourceState.EMPTY -> "Прочитан, пусто"
    SourceState.ACCESS_DENIED -> "Не хватает прав"
    SourceState.UNSUPPORTED -> "Недоступен на устройстве"
    SourceState.ERROR -> "Ошибка чтения"
    SourceState.TIMEOUT -> "Не ответил вовремя"
}

private fun observationTime(time: Long): String = SimpleDateFormat("HH:mm:ss · d MMM", Locale.forLanguageTag("ru")).format(Date(time))
