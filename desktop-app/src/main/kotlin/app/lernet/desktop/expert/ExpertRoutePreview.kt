package app.lernet.desktop.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyFlowMatcher
import app.lernet.routing.policy.PolicyPreviewConfidence
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyRoutePreview
import app.lernet.routing.policy.PolicySimulationInput

@Composable
internal fun ExpertRoutePreview(state: ExpertUiState, modifier: Modifier, onIntent: (ExpertIntent) -> Unit, openRoutes: () -> Unit) {
    var domain by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var process by remember { mutableStateOf("") }
    var network by remember { mutableStateOf<String?>(null) }
    var protocol by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var sourceIp by remember { mutableStateOf("") }
    var sourcePort by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var useDraft by remember { mutableStateOf(true) }
    val policy = if (useDraft) state.draft else state.saved
    val program = remember(policy, state.profiles, state.folders) {
        PolicyProgramCompiler.compile(
            policy,
            state.inventory, RoutePlatform.WINDOWS
        )
    }
    val input = PolicySimulationInput(
        domain = domain.trim().takeIf(String::isNotBlank), destinationIp = address.trim().takeIf(String::isNotBlank),
        processName = process.trim().takeIf(String::isNotBlank), network = network,
        protocol = protocol.trim().takeIf(String::isNotBlank), destinationPort = port.toIntOrNull(),
        geoCountry = country.trim().uppercase().takeIf(String::isNotBlank), sourceIp = sourceIp.trim().takeIf(String::isNotBlank),
        sourcePort = sourcePort.toIntOrNull(),
    )
    var preview by remember(policy, input) { mutableStateOf<PolicyRoutePreview?>(null) }
    val invalidPort = port.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } != true
    val invalidSourcePort = sourcePort.isNotBlank() && sourcePort.toIntOrNull()?.let { it in 1..65535 } != true
    val invalidCountry = country.isNotBlank() && !country.trim().matches(Regex("[A-Za-z]{2}"))
    ExpertScrollableColumn(modifier) {
        ExpertPanel("Проверка пути без сетевого запроса") {
            Text(
                "Укажите то, что известно о соединении. Проверка использует ту же скомпилированную схему, " +
                    "но не отправляет трафик и не доказывает работу выхода.",
                color = ExpertColors.muted, fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(useDraft, { useDraft = true }, label = { Text("Черновик") })
                FilterChip(!useDraft, { useDraft = false }, label = { Text("Сохранённая · ${state.saved.revision}") })
                ExpertTag("Локальный расчёт")
            }
            OutlinedTextField(domain, { domain = it }, Modifier.fillMaxWidth(), label = {
                Text("Имя сайта, если известно")
            }, singleLine = true)
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = {
                Text("IP назначения, если известен")
            }, singleLine = true)
            OutlinedTextField(process, { process = it }, Modifier.fillMaxWidth(), label = {
                Text("Имя процесса Windows")
            }, singleLine = true)
            ExpertSelect("Тип соединения", listOf(null to "Неизвестен", "tcp" to "TCP", "udp" to "UDP"), network) { network = it }
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Скрыть дополнительные данные" else "Дополнительные данные")
            }
            if (advanced) {
                OutlinedTextField(
                    port, { port = it }, Modifier.fillMaxWidth(), label = { Text("Порт назначения") },
                    singleLine = true, isError = invalidPort
                )
                OutlinedTextField(
                    protocol, { protocol = it }, Modifier.fillMaxWidth(), label = {
                        Text("Распознанный протокол, если известен")
                    }, singleLine = true,
                    supportingText = { Text("Например, tls, http, quic или dns. TCP и UDP задаются отдельно.") }
                )
                OutlinedTextField(
                    country, { country = it }, Modifier.fillMaxWidth(), label = {
                        Text("Страна IP, если известна")
                    }, singleLine = true,
                    isError = invalidCountry, supportingText = { Text("Двухбуквенный код. Страна не запрашивается автоматически.") }
                )
                OutlinedTextField(sourceIp, { sourceIp = it }, Modifier.fillMaxWidth(), label = {
                    Text("IP источника, если известен")
                }, singleLine = true)
                OutlinedTextField(
                    sourcePort, { sourcePort = it }, Modifier.fillMaxWidth(), label = {
                        Text("Порт источника")
                    }, singleLine = true,
                    isError = invalidSourcePort
                )
            }
            if (invalidPort || invalidSourcePort || invalidCountry) {
                ExpertMessage(
                    "Проверьте дополнительные данные",
                    "Порты должны быть от 1 до 65535, страна — двухбуквенный код. Откройте дополнительные данные, чтобы " +
                        "исправить поля.",
                    error = true
                )
            }
            Button(
                onClick = { preview = PolicyFlowMatcher.preview(program, input) },
                enabled = !invalidPort && !invalidSourcePort && !invalidCountry
            ) {
                Text("Проверить путь")
            }
        }
        preview?.let { result ->
            when (result.confidence) {
                PolicyPreviewConfidence.MATCHED -> {
                    val rule = requireNotNull(result.selected).rule
                    ExpertPanel("Выбранный путь") {
                        Text(ExpertPolicyEditing.targetName(rule.target.target, state), color = ExpertColors.green, fontSize = 20.sp)
                        ExpertTag(
                            if (rule.protected) "Прямой запасной путь запрещён" else "Защита не обязательна",
                            if (rule.protected) ExpertColors.green else ExpertColors.muted
                        )
                        rule.redirect?.let { redirect ->
                            Text(
                                "Перенаправление: ${redirect.address ?: "исходный адрес"}:${redirect.port?.toString() ?: "исходный порт"}",
                                color = ExpertColors.muted
                            )
                        }
                        PreviewSteps(result, state, policy, onIntent, openRoutes)
                    }
                }
                PolicyPreviewConfidence.INCOMPLETE -> ExpertPanel("Недостаточно данных для уверенного выбора") {
                    ExpertMessage(
                        "Ранние ветки могут изменить результат",
                        "Неизвестное условие перед подходящим правилом не считается ложным. " +
                            "Поэтому прямой запасной путь не объявляется выбранным.",
                        warning = true
                    )
                    result.missingFacts.forEach { Text("Нужно уточнить: $it", color = ExpertColors.muted) }
                    result.candidates.take(8).forEach { candidate ->
                        Text(
                            "Возможный шаг ${candidate.ruleIndex + 1}: " +
                                ExpertPolicyEditing.targetName(candidate.rule.target.target, state),
                            color = ExpertColors.muted, fontSize = 13.sp
                        )
                    }
                }
                PolicyPreviewConfidence.NO_MATCH -> ExpertMessage(
                    "Подходящих правил нет",
                    "В этой программе нет подтверждённого пути для заданных данных.", warning = true
                )
                PolicyPreviewConfidence.INVALID -> ExpertPanel("Сначала исправьте схему") {
                    result.errors.forEach { ExpertMessage("Ошибка схемы", it, error = true) }
                }
            }
        }
    }
}

@Composable
private fun PreviewSteps(
    result: PolicyRoutePreview,
    state: ExpertUiState,
    policy: NetworkPolicy,
    onIntent: (ExpertIntent) -> Unit,
    openRoutes: () -> Unit,
) {
    val allNodes = (listOf(policy.device) + policy.trees).flatMap { it.nodes }.associateBy { it.id }
    Text("Совпавшие условия", color = ExpertColors.text, fontWeight = FontWeight.SemiBold)
    if (result.nodeIds.isEmpty()) Text("Применён путь по умолчанию.", color = ExpertColors.muted)
    result.nodeIds.forEachIndexed { index, id ->
        Text("${index + 1}. ${allNodes[id]?.title?.ifBlank { "Без названия" } ?: "Правило"}", color = ExpertColors.muted)
    }
    result.scopes.distinct().forEach { scope ->
        TextButton(onClick = {
            onIntent(ExpertIntent.SelectScope(scope))
            openRoutes()
        }) {
            Text("Открыть: ${ExpertPolicyEditing.scopeName(scope, state)}")
        }
    }
}
