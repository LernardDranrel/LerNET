package app.lernet.desktop.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.PolicyDestinationAddress
import app.lernet.routing.policy.PolicyFlowMatcher
import app.lernet.routing.policy.PolicyMatchResult
import app.lernet.routing.policy.PolicyPreviewConfidence
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyRoutePreview
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicySimulationInput
import app.lernet.ui.routes.CountryNames
import kotlinx.coroutines.delay

private enum class PreviewVersion { DRAFT, SAVED, APPLIED, RECORDED }

@Composable
internal fun ExpertRoutePreview(
    state: ExpertUiState,
    modifier: Modifier,
    onIntent: (ExpertIntent) -> Unit,
    openRoutes: () -> Unit,
    recordedFlow: ExpertConnection? = null,
    showRecordedPath: Boolean = false,
    requestToken: Long = 0,
) {
    var example by rememberSaveable(stateSaver = ExpertInputSaver) { mutableStateOf(PolicySimulationInput()) }
    var history by rememberSaveable(stateSaver = ExpertConnectionSaver) { mutableStateOf<ExpertConnection?>(null) }
    var editable by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var calculated by rememberSaveable { mutableStateOf(false) }
    var version by rememberSaveable { mutableStateOf(if (state.applied != null) PreviewVersion.APPLIED else PreviewVersion.SAVED) }
    var showRecorded by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var port by rememberSaveable { mutableStateOf("") }
    var sourcePort by rememberSaveable { mutableStateOf("") }
    fun load(flow: ExpertConnection) {
        history = flow
        example = flow.simulationInput()
        port = example.destinationPort?.toString().orEmpty()
        sourcePort = example.sourcePort?.toString().orEmpty()
        editable = false
        editorOpen = false
        calculated = false
        version = PreviewVersion.RECORDED
        showRecorded = true
    }
    var lastSeedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordedFlow?.id, requestToken, showRecordedPath) {
        recordedFlow?.let { flow ->
            val seed = "${flow.id}:$requestToken:$showRecordedPath"
            if (seed != lastSeedId) {
                load(flow)
                showRecorded = showRecordedPath
                if (!showRecordedPath) version = if (state.applied != null) PreviewVersion.APPLIED else PreviewVersion.SAVED
                lastSeedId = seed
            }
        }
    }
    val recordedPolicy = state.recordedPolicy(history?.policyRevision)
    val policy = when (version) {
        PreviewVersion.DRAFT -> state.draft
        PreviewVersion.SAVED, PreviewVersion.APPLIED -> state.applied ?: state.saved
        PreviewVersion.RECORDED -> recordedPolicy
    }
    val program = remember(policy, state.inventory) {
        policy?.let { PolicyProgramCompiler.compile(it, state.inventory, RoutePlatform.WINDOWS) }
    }
    val input = example.copy(destinationPort = port.toIntOrNull(), sourcePort = sourcePort.toIntOrNull())
    var preview by remember(policy, input, program) { mutableStateOf<PolicyRoutePreview?>(null) }
    var candidateIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedCandidate = preview?.takeIf { it.confidence == PolicyPreviewConfidence.INCOMPLETE }
        ?.candidates?.firstOrNull { it.ruleIndex == candidateIndex }
    val knownNodeIds = policy?.let { (listOf(it.device) + it.trees).flatMap { tree -> tree.nodes.map { node -> node.id } }.toSet() }.orEmpty()
    val unresolvedRecordedNodes = history?.routeNodeIds.orEmpty().filter { it !in knownNodeIds }
    val recordedPathAvailable = showRecorded && policy != null && policy == recordedPolicy && unresolvedRecordedNodes.isEmpty()
    val pathIds = if (recordedPathAvailable) history?.routeNodeIds.orEmpty() else if (!showRecorded) selectedCandidate?.rule?.nodeIds ?: preview?.nodeIds.orEmpty() else emptyList()
    var shownNodes by remember(pathIds) { mutableStateOf(emptySet<String>()) }
    var shownScope by remember(policy, preview, showRecorded, candidateIndex) {
        mutableStateOf(if (recordedPathAvailable) (listOfNotNull(policy?.device) + policy?.trees.orEmpty())
            .firstOrNull { tree -> tree.nodes.any { it.id in pathIds } }?.scope ?: PolicyScope.Device
            else selectedCandidate?.rule?.scopes?.firstOrNull() ?: preview?.scopes?.firstOrNull() ?: PolicyScope.Device)
    }
    val invalidPort = port.isNotBlank() && port.toIntOrNull()?.let { it in 0..65535 } != true
    val invalidSourcePort = sourcePort.isNotBlank() && sourcePort.toIntOrNull()?.let { it in 0..65535 } != true
    val invalidAddress = !validPreviewIp(example.destinationIp) || !validPreviewIp(example.sourceIp)
    LaunchedEffect(program, input, calculated, invalidPort, invalidSourcePort, invalidAddress) {
        preview = if (calculated && !invalidPort && !invalidSourcePort && !invalidAddress) program?.let { PolicyFlowMatcher.preview(it, input) } else null
    }
    LaunchedEffect(pathIds, state.reducedMotion) {
        shownNodes = emptySet()
        pathIds.forEach { id ->
            if (!state.reducedMotion) delay(180)
            shownNodes = shownNodes + id
        }
    }
    ExpertScrollableColumn(modifier) {
        ExpertPanel("Симулятор пути") {
            Text("Выберите соединение из истории, чтобы увидеть его путь или проверить его по текущей схеме. Проверка не отправляет трафик.",
                color = ExpertColors.muted, fontSize = 13.sp)
            ExpertSelect<String?>("Соединение из истории", listOf(null to "Выберите запись") + state.connections.map {
                it.id to "${it.application} · ${it.destination} · ${it.protocol}"
            }, history?.id) { id -> state.connections.firstOrNull { it.id == id }?.let(::load) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    history = null
                    example = PolicySimulationInput()
                    port = ""
                    sourcePort = ""
                    editable = true
                    editorOpen = true
                    version = if (state.applied != null) PreviewVersion.APPLIED else PreviewVersion.SAVED
                    showRecorded = false
                    calculated = false
                }) { Text("Создать пример") }
                TextButton(onClick = {
                    editable = true; editorOpen = !editorOpen; showRecorded = false
                    version = if (state.applied != null) PreviewVersion.APPLIED else PreviewVersion.SAVED
                    calculated = false
                }, enabled = history != null || editable) { Text(if (editorOpen) "Скрыть параметры" else "Изменить параметры") }
                ExpertTag(if (history == null) "Новый пример" else if (editable) "Изменённая копия" else "Запись истории")
            }
            history?.let { flow ->
                Text("${flow.application} → ${flow.destination} · ${flow.protocol}", color = ExpertColors.text)
                Text("Наблюдалось: ${flow.decision}", color = ExpertColors.muted, fontSize = 12.sp)
                TextButton(onClick = {
                    showRecorded = version != PreviewVersion.RECORDED || !showRecorded
                    version = PreviewVersion.RECORDED
                    editorOpen = false
                    calculated = false
                }) { Text(if (version == PreviewVersion.RECORDED && showRecorded) "Скрыть путь" else "Как прошло тогда · показать путь") }
            }
            if (history == null && !editable) Text(
                if (state.connections.isEmpty()) "История пока пуста. После включения туннеля здесь появятся соединения. Можно также создать свой пример."
                else "Выберите соединение выше. Его исходные данные останутся неизменными.", color = ExpertColors.muted)
            if (history != null || editable) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(version == PreviewVersion.APPLIED || version == PreviewVersion.SAVED, {
                    version = if (state.applied != null) PreviewVersion.APPLIED else PreviewVersion.SAVED
                    showRecorded = false; calculated = true
                }, label = { Text("Как пройдёт по текущей схеме") })
                if (state.hasDraftChanges) FilterChip(version == PreviewVersion.DRAFT, {
                    version = PreviewVersion.DRAFT; showRecorded = false; calculated = true
                }, label = { Text("С учётом несохранённых правок") })
            }
            if (showRecorded && policy != null && unresolvedRecordedNodes.isNotEmpty()) ExpertMessage("Узлы записанного пути недоступны",
                "Узлов записи нет в доступной схеме. Подсветка не подтверждена; можно выполнить новый расчёт.", warning = true)
            if (history != null && version == PreviewVersion.RECORDED && policy == null) ExpertMessage("Записанная схема недоступна",
                "Снимок схемы этого соединения не сохранён. Его прежний путь нельзя подсветить, но можно выполнить расчёт по текущей схеме.", warning = true)
            if (editorOpen) {
                OutlinedTextField(example.domain.orEmpty(), { example = example.copy(domain = it.known()) }, Modifier.fillMaxWidth(),
                    label = { Text("Домен, если известен") }, singleLine = true, enabled = editable)
                OutlinedTextField(example.destinationIp.orEmpty(), { example = example.copy(destinationIp = it.known()) }, Modifier.fillMaxWidth(),
                    label = { Text("IP назначения, если известен") }, singleLine = true, enabled = editable,
                    isError = !validPreviewIp(example.destinationIp))
                OutlinedTextField(example.processName.orEmpty(), { example = example.copy(processName = it.known()) }, Modifier.fillMaxWidth(),
                    label = { Text("Имя процесса Windows, если известно") }, singleLine = true, enabled = editable)
                if (editable) ExpertSelect("Транспорт", listOf(null to "Неизвестен", "tcp" to "TCP", "udp" to "UDP", "icmp" to "ICMP"),
                    example.network) { example = example.copy(network = it) }
                else Text("Транспорт: ${example.network ?: "неизвестен"}", color = ExpertColors.muted)
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Скрыть дополнительные данные" else "Дополнительные данные") }
                if (advanced) {
                    OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth(), label = { Text("Порт назначения") },
                        singleLine = true, isError = invalidPort, enabled = editable)
                    if (editable) {
                        val protocols = listOf(null to "Неизвестен", "tls" to "TLS", "http" to "HTTP", "quic" to "QUIC", "dns" to "DNS")
                        ExpertSelect("Распознанный протокол", protocols + listOfNotNull(example.protocol?.takeIf { value ->
                            protocols.none { it.first == value }
                        }?.let { it to it }), example.protocol) { example = example.copy(protocol = it) }
                        ExpertSelect<String?>("Страна IP", listOf(null to "Неизвестна") + CountryNames.all.map {
                            it.code.uppercase() to "${it.code.uppercase()} · ${it.nameRu}"
                        }, example.geoCountry?.uppercase()) { example = example.copy(geoCountry = it) }
                    } else {
                        Text("Распознанный протокол: ${example.protocol ?: "неизвестен"}", color = ExpertColors.muted)
                        Text("Страна IP: ${example.geoCountry ?: "неизвестна"}", color = ExpertColors.muted)
                    }
                    OutlinedTextField(example.sourceIp.orEmpty(), { example = example.copy(sourceIp = it.known()) }, Modifier.fillMaxWidth(),
                        label = { Text("IP источника, если известен") }, singleLine = true, enabled = editable,
                        isError = !validPreviewIp(example.sourceIp))
                    OutlinedTextField(sourcePort, { sourcePort = it }, Modifier.fillMaxWidth(), label = { Text("Порт источника") },
                        singleLine = true, isError = invalidSourcePort, enabled = editable)
                }
            }
            if (invalidPort || invalidSourcePort || invalidAddress) ExpertMessage("Проверьте параметры примера",
                "Откройте параметры: порты должны быть от 0 до 65535, адреса — числовыми IPv4 или IPv6.", error = true)
            if (history != null || editable) Button(onClick = {
                if (version == PreviewVersion.RECORDED) version = if (state.applied != null) PreviewVersion.APPLIED else PreviewVersion.SAVED
                showRecorded = false; calculated = true
            }, enabled = !invalidPort && !invalidSourcePort && !invalidAddress) { Text("Проверить по текущей схеме") }
        }
        preview?.takeIf { !showRecorded }?.let { result ->
            ExpertPanel("Результат локального расчёта") {
                when (result.confidence) {
                    PolicyPreviewConfidence.MATCHED -> {
                        val rule = requireNotNull(result.selected).rule
                        Text(ExpertPolicyEditing.targetName(rule.target.target, state), color = ExpertColors.green, fontSize = 20.sp)
                        if (rule.protected) ExpertTag("Прямой выход запрещён для всей ветки")
                        rule.redirect?.let { redirect -> Text("Перенаправление: ${redirect.address ?: "исходный адрес"}:${redirect.port ?: "исходный порт"}",
                            color = ExpertColors.muted) }
                        if (result.nodeIds.isEmpty()) Text("Применён путь по умолчанию.", color = ExpertColors.muted)
                    }
                    PolicyPreviewConfidence.INCOMPLETE -> {
                        ExpertMessage("Недостаточно данных", "Неизвестная ранняя ветка может изменить результат. Подтверждённый путь не выбран.", warning = true)
                        result.missingFacts.forEach { Text("Нужно уточнить: $it", color = ExpertColors.muted) }
                        ExpertSelect<Int?>("Возможные ветки", listOf(null to "Выберите ветку для просмотра") + result.candidates.map {
                            it.ruleIndex to "Шаг ${it.ruleIndex + 1} · ${ExpertPolicyEditing.targetName(it.rule.target.target, state)}"
                        }, candidateIndex) { candidateIndex = it }
                        selectedCandidate?.let { candidate ->
                            Text(if (candidate.match.result == PolicyMatchResult.UNKNOWN) "Условия этой ветки известны не полностью."
                                else "Условия этой ветки совпали, но более ранняя неизвестная ветка может изменить выбор.",
                                color = ExpertColors.amber)
                            candidate.match.missingFacts.forEach { Text("Для этой ветки нужно: $it", color = ExpertColors.muted) }
                        }
                    }
                    PolicyPreviewConfidence.NO_MATCH -> ExpertMessage("Подходящих правил нет", "Подтверждённый путь не найден.", warning = true)
                    PolicyPreviewConfidence.INVALID -> result.errors.forEach { ExpertMessage("Ошибка схемы", it, error = true) }
                }
            }
        }
        policy?.takeIf { recordedPathAvailable || !showRecorded && preview != null }?.let { selectedPolicy ->
            ExpertPanel(if (showRecorded) "Как прошло тогда"
                else if (selectedCandidate != null) "Возможная ветка · не подтверждена"
                else "Как пройдёт по схеме") {
                if (showRecorded && pathIds.isEmpty()) Text("Узлы пути не переданы ядром. Подсветка недоступна.", color = ExpertColors.muted)
                val scopes = (listOf(PolicyScope.Device) + selectedPolicy.trees.map { it.scope }).distinct()
                ExpertSelect("Участок пути", scopes.map { it to ExpertPolicyEditing.scopeName(it, state) }, shownScope) {
                    shownScope = it
                }
                val graphState = state.copy(draft = selectedPolicy, selectedScope = shownScope)
                ExpertGraph(ExpertPolicyEditing.tree(selectedPolicy, shownScope), graphState, null,
                    Modifier.fillMaxWidth().height(380.dp), select = {}, channelEdit = {}, defaultEdit = {},
                    highlightedNodeIds = shownNodes, highlightedRoot = !showRecorded && (preview?.confidence == PolicyPreviewConfidence.MATCHED || selectedCandidate != null),
                    highlightedDefaultTarget = !showRecorded && (preview?.confidence == PolicyPreviewConfidence.MATCHED && preview?.nodeIds?.isEmpty() == true ||
                        selectedCandidate?.rule?.nodeIds?.isEmpty() == true), possiblePath = selectedCandidate != null,
                    readOnly = true)
                if (!showRecorded && preview?.confidence == PolicyPreviewConfidence.MATCHED) preview?.scopes.orEmpty().distinct().forEach { scope ->
                    TextButton(onClick = { onIntent(ExpertIntent.SelectScope(scope)); openRoutes() }) {
                        Text("Редактировать: ${ExpertPolicyEditing.scopeName(scope, state)}")
                    }
                }
            }
        }

    }
}

private fun String.known(): String? = trim().takeIf(String::isNotBlank)

private fun validPreviewIp(value: String?): Boolean = value == null ||
    value.any { it == ':' || it == '.' } && value.all { it in "0123456789abcdefABCDEF:." } &&
    PolicyDestinationAddress.normalize(value) != null
