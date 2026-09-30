package app.lernet.ui.network

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lernet.engine.net.observation.*
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.motion.motionTween
import app.lernet.ui.motion.rememberReduceMotion
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Mint = Color(0xFF80DEBE)
private val Amber = Color(0xFFFFD58A)

@Composable
fun NetworkObservationRoute(onBack: () -> Unit) {
    val vm: NetworkObservationViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::export)
    }
    NetworkObservationScreen(state, onBack, vm::refresh, vm::checkExternalIp,
        onExport = { export.launch("LerNET-network-${state.snapshot?.finishedAt ?: System.currentTimeMillis()}.json") })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun NetworkObservationScreen(
    state: NetworkObservationUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCheckIp: () -> Unit,
    onExport: () -> Unit,
) {
    val systemReducedMotion = rememberReduceMotion()
    var pauseMotion by remember { mutableStateOf(false) }
    val reduceMotion = systemReducedMotion || pauseMotion
    var tab by remember { mutableIntStateOf(0) }
    var selectedSource by remember { mutableStateOf("adapters") }
    var confirmIp by remember { mutableStateOf(false) }
    var expandedFinding by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun selectTab(index: Int) {
        tab = index
        scope.launch { if (reduceMotion) listState.scrollToItem(1) else listState.animateScrollToItem(1) }
    }
    val snapshot = state.snapshot
    val changes = remember(state.previous, snapshot) { observationChanges(state.previous, snapshot) }
    val sources = snapshot?.sources.orEmpty()
    val detail = sources.firstOrNull { it.id == selectedSource }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Сеть устройства") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(LerNetSymbols.arrowBack(), "Назад") }
            }, actions = {
                IconButton(onClick = onRefresh, enabled = !state.collecting) { Icon(LerNetSymbols.probe(), "Обновить локальный снимок") }
                IconButton(onClick = onExport, enabled = snapshot != null && !state.collecting) { Icon(LerNetSymbols.download(), "Сохранить снимок в файл") }
            })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Разберём, куда идёт интернет", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Нажмите на участок пути: настройки, их смысл и факты будут рядом.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (snapshot == null) "Локальный снимок" else "Снимок ${time(snapshot.finishedAt)} · Android", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pauseMotion = !pauseMotion }, enabled = !systemReducedMotion) {
                        Text(if (reduceMotion) "Без движения" else "Анимации вкл.", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (state.collecting) {
                    if (!reduceMotion) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (snapshot == null) "Читаем настройки, без сетевых запросов…" else "Обновляем. Ниже — предыдущий снимок.",
                        modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            stickyHeader {
                SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    listOf("Обзор", "Данные", "Изменения").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { selectTab(index) }, text = { Text(title) })
                    }
                }
            }
            if (snapshot == null && !state.collecting) item {
                Text("Снимок ещё не получен. Обновите данные, чтобы увидеть настройки сети.")
                Button(onClick = onRefresh) { Text("Прочитать настройки") }
            }
            when (tab) {
                0 -> {
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            PathStep("01", "Устройство", "Локальные адреса и интерфейсы", sources.firstOrNull { it.id == "adapters" }?.rows?.size?.let { "Видимых сетей: $it" } ?: "Ожидаем снимок",
                                selectedSource == "adapters", reduceMotion) { selectedSource = "adapters" }
                            PathConnector()
                            PathStep("02", "Путь", "Маршруты и видимый VPN", sources.firstOrNull { it.id == "vpn" }?.rows?.let { if (it.isEmpty()) "VPN не виден в этой области" else "Виден VPN · ${it.size}" } ?: "Ожидаем снимок",
                                selectedSource == "routes" || selectedSource == "vpn", reduceMotion) { selectedSource = "routes" }
                            PathConnector()
                            PathStep("03", "Имена сайтов", "DNS, Private DNS и прокси", "Кто помогает найти адрес назначения",
                                selectedSource == "dns", reduceMotion) { selectedSource = "dns" }
                            Text("Схема настроек, не трассировка пакетов", modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (detail != null) item {
                        if (selectedSource == "routes") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = true, onClick = { selectedSource = "routes" }, label = { Text("Маршруты") })
                            FilterChip(selected = false, onClick = { selectedSource = "vpn" }, label = { Text("VPN") })
                        }
                        if (selectedSource == "vpn") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = false, onClick = { selectedSource = "routes" }, label = { Text("Маршруты") })
                            FilterChip(selected = true, onClick = { selectedSource = "vpn" }, label = { Text("VPN") })
                        }
                        SourcePanel(detail, reduceMotion, snapshot = snapshot)
                    }
                    item {
                        OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Как нас видит интернет?", style = MaterialTheme.typography.titleMedium)
                                Text("Внешний IP нельзя узнать только из локальных настроек. Проверка обращается к одному сервису по текущему пути Android.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                state.externalIp?.let { ip -> SelectionContainer { Text(ip, style = MaterialTheme.typography.headlineSmall, color = Mint) }
                                    Text("api.ipify.org · ${time(state.externalIpAt ?: 0)} · ${if (':' in ip) "IPv6" else "IPv4"}", style = MaterialTheme.typography.labelSmall)
                                    Text("Страна: ${state.countryCode?.let { Locale("", it).getDisplayCountry(Locale.getDefault()) } ?: "не найдена в локальной базе"} · по локальной IP-базе, не GPS",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("Провайдер: данных в локальном справочнике нет. IP может принадлежать выходному серверу VPN.", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                state.ipError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                FilledTonalButton(onClick = { confirmIp = true }, enabled = !state.checkingIp && !state.collecting) {
                                    Text(if (state.checkingIp) "Проверяем IP…" else "Проверить внешний IP")
                                }
                            }
                        }
                    }
                    item { Text("Что видно в снимке", style = MaterialTheme.typography.titleLarge) }
                    items(snapshot?.findings.orEmpty(), key = { it.code }) { original ->
                        val finding = snapshot?.let { ObservationFindingGuide.explain(it, original) } ?: original
                        val expanded = expandedFinding == finding.code
                        OutlinedCard(Modifier.fillMaxWidth().animateContentSize(motionTween(reduceMotion, 220))) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expandedFinding = if (expanded) null else finding.code }
                                    .semantics { stateDescription = if (expanded) "Разбор раскрыт" else "Разобраться в наблюдении" },
                                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (finding.kind == FindingKind.POTENTIAL_CONFLICT) LerNetSymbols.help() else LerNetSymbols.route(), null,
                                        tint = if (finding.kind == FindingKind.POTENTIAL_CONFLICT) Amber else MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(when (finding.kind) { FindingKind.FACT -> "Факт"; FindingKind.POTENTIAL_CONFLICT -> "Стоит проверить"; FindingKind.INSUFFICIENT_DATA -> "Пробелы данных" },
                                            style = MaterialTheme.typography.labelMedium, color = if (finding.kind == FindingKind.POTENTIAL_CONFLICT) Amber else MaterialTheme.colorScheme.primary)
                                        Text(finding.title, style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(if (expanded) "↑" else "↓", color = MaterialTheme.colorScheme.primary)
                                }
                                Text(finding.explanation, style = MaterialTheme.typography.bodyMedium)
                                AnimatedVisibility(expanded, enter = androidx.compose.animation.fadeIn(motionTween(reduceMotion, 160)), exit = androidx.compose.animation.fadeOut(motionTween(reduceMotion, 100))) {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        MobileReadingBlock("Как мы это определили", finding.reason)
                                        MobileReadingBlock("На что это может повлиять", finding.impact)
                                        MobileReadingBlock("Что проверить дальше", finding.nextSteps.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n\n"))
                                        MobileFindingObjects(finding)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            finding.sourceIds.forEach { id -> TextButton(onClick = { selectedSource = id; selectTab(1) }) {
                                                Text(sources.firstOrNull { it.id == id }?.title ?: id)
                                            } }
                                        }
                                    }
                                }
                            }
                        }
                    }

                }
                1 -> {
                    item {
                        Text("Настройки, которые раскрывает Android", style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sources.forEach { source -> FilterChip(selected = selectedSource == source.id, onClick = { selectedSource = source.id }, label = { Text(source.title) }) }
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Найти в выбранном источнике") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    detail?.let { source -> item { SourcePanel(source, reduceMotion, query, snapshot) } }
                }
                2 -> {
                    item {
                        Text("Что поменялось?", style = MaterialTheme.typography.titleLarge)
                        Text(if (state.previous == null) "Нужны два снимка. Нажмите обновление после смены сети или настройки — здесь появится разница."
                            else "Сравниваем ${time(state.previous.finishedAt)} → ${time(snapshot?.finishedAt ?: 0)}. Изменение настройки не говорит, кто её изменил.",
                            modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.previous != null && changes.isEmpty()) Text("В доступных источниках различий нет.", modifier = Modifier.padding(top = 12.dp), color = Mint)
                    }
                    items(changes) { change ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(change.title, style = MaterialTheme.typography.titleMedium)
                                MobileReadingBlock("Что описывает источник", ObservationGuide.source(change.sourceId, "Android").meaning)
                                MobileReadingBlock("Как понимать изменение", "Сравниваем два снимка. Изменение настройки или доступности данных не показывает, кто его сделал и работает ли соединение.")
                                SelectionContainer { Column { Text("Было · ${change.before}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp)); Text("Стало · ${change.after}", style = MaterialTheme.typography.bodySmall, color = Mint) } }
                            }
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("Только наблюдение", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge, color = Mint)
                Text("Обновление читает настройки на устройстве. Оно не включает VPN, не меняет маршруты и не отправляет снимок. Android показывает не все сети и не раскрывает настройки чужого VPN.",
                    modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.notice?.let { Text(it, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (confirmIp) AlertDialog(onDismissRequest = { confirmIp = false }, title = { Text("Проверить внешний IP?") },
        text = { Text("LerNET отправит HTTPS-запрос к api.ipify.org. Сервис увидит IP этого запроса; локальный снимок и настройки не передаются. Используется текущий путь Android — без обхода VPN. Это проверка одного адреса, а не гарантия защиты всего трафика.") },
        confirmButton = { TextButton(onClick = { confirmIp = false; onCheckIp() }) { Text("Проверить") } },
        dismissButton = { TextButton(onClick = { confirmIp = false }) { Text("Отмена") } })
}

@Composable
private fun PathStep(number: String, title: String, subtitle: String, status: String, selected: Boolean, reduceMotion: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = motionTween(reduceMotion, 200), label = "Выбор участка сети")
    Surface(onClick = onClick, color = color, contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Text(number, style = MaterialTheme.typography.titleMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.labelMedium)
            }
            Text(if (selected) "✓" else "›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PathConnector() {
    Row(Modifier.padding(start = 29.dp).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("↓", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SourcePanel(source: ObservationSource, reduceMotion: Boolean, query: String = "", snapshot: NetworkSnapshot? = null) {
    val rows = remember(source, query) { source.rows.filter { row -> query.isBlank() || "${row.title} ${row.fields}".contains(query, ignoreCase = true) } }
    var count by remember(source.id, query) { mutableStateOf(8) }
    var help by remember(source.id) { mutableStateOf(false) }
    val guide = ObservationGuide.source(source.id, "Android")
    Column(Modifier.fillMaxWidth().animateContentSize(motionTween(reduceMotion, 220)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(source.title, style = MaterialTheme.typography.titleLarge)
        Text(guide.meaning, style = MaterialTheme.typography.bodyMedium)
        Text("${sourceStateLabel(source.state)}${if (!source.complete) " · неполные данные" else ""} · ${time(source.capturedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        MobileReadingBlock("Результат чтения", ObservationGuide.availability(source))
        if (source.detail.isNotBlank()) MobileReadingBlock("Причина, указанная источником", source.detail)
        TextButton(onClick = { help = !help }) { Text(if (help) "Скрыть инструкцию ↑" else "Как читать эти данные ↓") }
        AnimatedVisibility(help, enter = androidx.compose.animation.fadeIn(motionTween(reduceMotion, 160)), exit = androidx.compose.animation.fadeOut(motionTween(reduceMotion, 100))) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MobileReadingBlock("На что смотрим", guide.inspect)
                MobileReadingBlock("Когда стоит проверить", guide.attention)
                MobileReadingBlock("Чего данные не доказывают", guide.limits)
            }
        }
        if (rows.isEmpty()) Text(if (query.isNotBlank()) "Ничего не найдено по этому запросу." else if (source.state == SourceState.EMPTY) "Android не передал записей в доступной области." else "Нет доступных записей.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.take(count).forEach { row -> MobileEvidenceRow(row, source.id, snapshot, reduceMotion) }
        if (rows.size > count) TextButton(onClick = { count += 8 }) { Text("Показать ещё ${minOf(8, rows.size - count)} записей") }
    }
}

@Composable
private fun MobileEvidenceRow(row: EvidenceRow, sourceId: String, snapshot: NetworkSnapshot?, reduceMotion: Boolean) {
    var expanded by remember(row.id) { mutableStateOf(false) }
    var fields by remember(row.id) { mutableStateOf(false) }
    val guide = ObservationGuide.row(sourceId, row, snapshot)
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize(motionTween(reduceMotion, 180))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Запись раскрыта" else "Показать пояснение записи" }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    if (guide.summary != row.title) Text(guide.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "↑" else "↓", color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                MobileReadingBlock("Что означает эта запись", guide.interpretation)
                TextButton(onClick = { fields = !fields }) { Text(if (fields) "Скрыть поля ↑" else "Поля и их смысл · ${row.fields.size} ↓") }
                if (fields) row.fields.forEach { (key, value) ->
                    val field = ObservationGuide.field(key)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(field.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(ObservationGuide.fieldValue(key, value), style = MaterialTheme.typography.bodyLarge) }
                            Text(field.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text("Поле ОС: $key${if (ObservationGuide.fieldValue(key, value) != value) " · исходное: $value" else ""}", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileReadingBlock(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MobileFindingObjects(finding: NetworkFinding) {
    var count by remember(finding.code) { mutableStateOf(3) }
    if (finding.relatedItems.isNotEmpty()) {
        finding.relatedItems.take(count).forEach { row ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                SelectionContainer { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall)
                    row.fields.forEach { (key, value) -> MobileReadingBlock(key, value) }
                } }
            }
        }
        if (count < finding.relatedItems.size) TextButton(onClick = { count += 6 }) { Text("Ещё объекты") }
    } else {
        finding.evidence.take(count).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (count < finding.evidence.size) TextButton(onClick = { count += 6 }) { Text("Ещё основания") }
    }
}

internal fun observationChanges(previous: NetworkSnapshot?, current: NetworkSnapshot?): List<SnapshotChange> {
    if (previous == null || current == null) return emptyList()
    return NetworkObservationAnalysis.compare(previous, current)
}

private fun sourceStateLabel(state: SourceState) = when (state) {
    SourceState.AVAILABLE -> "Прочитано"; SourceState.EMPTY -> "Записей нет"; SourceState.ACCESS_DENIED -> "Доступ закрыт"
    SourceState.UNSUPPORTED -> "Ограничение платформы"; SourceState.ERROR -> "Ошибка чтения"; SourceState.TIMEOUT -> "Время чтения истекло"
}
private fun time(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
