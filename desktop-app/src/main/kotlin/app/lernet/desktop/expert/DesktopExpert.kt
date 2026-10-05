@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.lernet.desktop.expert

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.routing.policy.PolicyScope

private enum class ExpertPage(val title: String) {
    OVERVIEW("Обзор"),
    ROUTES("Схема"),
    PREVIEW("Проверка пути"),
    EXITS("Выходы"),
    TRAFFIC("Соединения"),
    EVENTS("События"),
    PROTECTION("Защита")
}

/** The controller alone starts networking and acknowledges applied revisions. */
@Composable
fun DesktopExpert(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf(ExpertPage.OVERVIEW) }
    var confirmation by remember { mutableStateOf<ExpertIntent?>(null) }
    val validation = remember(state.draft, state.profiles, state.folders) { ExpertPolicyEditing.validation(state) }
    Column(
        modifier.fillMaxSize().background(ExpertColors.background).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                event.isCtrlPressed &&
                event.key == Key.S &&
                state.hasDraftChanges &&
                validation.isEmpty() &&
                !state.busy
            ) {
                onIntent(ExpertIntent.SaveDraft)
                true
            } else {
                false
            }
        },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NetworkGlyph()
            Column(Modifier.weight(1f)) {
                Text("Управление сетью", color = ExpertColors.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text("Один TUN. Ваши правила для каждого соединения.", color = ExpertColors.muted, fontSize = 13.sp)
            }
            ExpertTag(phaseName(state.phase), if (state.phase == ExpertPhase.RUNNING) ExpertColors.green else ExpertColors.muted)
            TextButton(onClick = { onIntent(ExpertIntent.OpenNetworkObservation) }) { Text("Сеть устройства") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpertPage.entries.forEach { item ->
                FilterChip(selected = page == item, onClick = { page = item }, label = { Text(item.title) })
            }
        }
        state.error?.let { ExpertMessage("Операция не завершена", it, error = true) }
        state.notice?.let { ExpertMessage("Состояние сети", it) }
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("Ожидаем подтверждение обработчика…", color = ExpertColors.muted)
            }
        }
        when (page) {
            ExpertPage.OVERVIEW -> ExpertOverview(state, onIntent, Modifier.weight(1f), { page = ExpertPage.ROUTES }, { confirmation = it })
            ExpertPage.ROUTES -> ExpertRoutes(state, onIntent, Modifier.weight(1f))
            ExpertPage.PREVIEW -> ExpertRoutePreview(state, Modifier.weight(1f), onIntent) { page = ExpertPage.ROUTES }
            ExpertPage.EXITS -> ExpertExits(state, onIntent, Modifier.weight(1f)) { page = ExpertPage.ROUTES }
            ExpertPage.TRAFFIC -> ExpertTraffic(state, onIntent, Modifier.weight(1f)) { page = ExpertPage.ROUTES }
            ExpertPage.EVENTS -> ExpertEvents(state, onIntent, Modifier.weight(1f))
            ExpertPage.PROTECTION -> ExpertProtectionPage(state, onIntent, Modifier.weight(1f)) { confirmation = it }
        }
        if (state.hasDraftChanges || (state.phase == ExpertPhase.RUNNING && state.hasUnappliedChanges)) {
            DraftActions(state, validation, onIntent) { confirmation = it }
        }
    }
    when (confirmation) {
        ExpertIntent.Stop -> ExpertConfirm(
            "Остановить управление сетью?",
            "TUN будет остановлен. Правила внутри него перестанут действовать. " +
                if (state.protection.systemGuardEnforced) {
                    "Системная защита останется включённой и заблокирует Интернет. " +
                        "Чтобы вернуть обычную сеть, отдельно отключите защиту."
                } else {
                    "Защита вне TUN не подтверждена: после остановки трафик может идти напрямую. " +
                        "Системные ограничения, если они остались, снимаются отдельно."
                },
            "Остановить", {
                confirmation = null
                onIntent(ExpertIntent.Stop)
            }, { confirmation = null },
        )
        ExpertIntent.DiscardDraft -> ExpertConfirm(
            "Отменить изменения?", "Черновик будет заменён последней сохранённой схемой. Активная версия сети не изменится.",
            "Отменить изменения", {
                confirmation = null
                onIntent(ExpertIntent.DiscardDraft)
            }, { confirmation = null },
        )
        ExpertIntent.ApplySaved -> ExpertConfirm(
            "Применить сохранённую схему?",
            "После подтверждения новые соединения будут использовать версию ${state.saved.revision} на том же TUN. " +
                "Если ответ обработчика потеряется, состояние правил нельзя считать известным: " +
                "приложение покажет ошибку и проверит состояние либо остановит TUN.",
            "Применить", {
                confirmation = null
                onIntent(ExpertIntent.ApplySaved)
            }, { confirmation = null },
        )
        ExpertIntent.RestoreAppliedToDraft -> ExpertConfirm(
            "Вернуть применённую схему в редактор?",
            "Текущий черновик будет заменён схемой версии ${state.appliedRevision}. " +
                "Сохранённая версия и работающий TUN не изменятся. Затем схему можно отредактировать или сохранить.",
            "Вернуть в черновик", {
                confirmation = null
                onIntent(ExpertIntent.RestoreAppliedToDraft)
            }, { confirmation = null },
        )
        ExpertIntent.DisableSystemGuard -> ExpertConfirm(
            "Отключить системную защиту?",
            "Защита больше не сможет блокировать обход после остановки TUN. Защищённые ветки внутри работающего TUN " +
                "останутся настроенными.",
            "Отключить защиту", {
                confirmation = null
                onIntent(ExpertIntent.DisableSystemGuard)
            }, { confirmation = null },
        )
        null -> Unit
        is ExpertIntent.EditPolicy, is ExpertIntent.SelectScope, ExpertIntent.SaveDraft, ExpertIntent.Start,
        ExpertIntent.Refresh, ExpertIntent.OpenNetworkObservation, ExpertIntent.Import, ExpertIntent.Export,
        ExpertIntent.ChooseExecutable, ExpertIntent.EnableSystemGuard, ExpertIntent.RecoverSystemGuard,
        ExpertIntent.RefreshInterfaces, is ExpertIntent.SaveExternalExit,
        is ExpertIntent.WakeExit, is ExpertIntent.SleepExit -> Unit
    }
}

@Composable
private fun ExpertOverview(
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    modifier: Modifier,
    openRoutes: () -> Unit,
    confirm: (ExpertIntent) -> Unit,
) {
    var healthSettings by remember { mutableStateOf(false) }
    ExpertScrollableColumn(modifier.fillMaxWidth()) {
        ExpertPanel("Путь всего устройства", reducedMotion = state.reducedMotion) {
            Text(
                when (state.phase) {
                    ExpertPhase.STOPPED -> "Включите управление, когда схема готова"
                    ExpertPhase.STARTING -> "Проверяем обработчик и создаём TUN"
                    ExpertPhase.RUNNING, ExpertPhase.APPLYING -> "Сеть проходит через LerNET"
                    ExpertPhase.STOPPING -> "Завершаем управление сетью"
                    ExpertPhase.FAILED -> "Работа обработчика не подтверждена"
                },
                color = ExpertColors.text, fontSize = 23.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                "Правила определяют, какие соединения идут напрямую, блокируются или направляются " +
                    "в отдельный профиль, папку либо общий канал. Обычный VPN и Эксперт используют один сетевой обработчик.",
                color = ExpertColors.muted, fontSize = 14.sp, lineHeight = 21.sp,
            )
            if (!state.administrator) {
                ExpertMessage(
                    "Нужны права администратора",
                    "Windows требует их для создания TUN. Запуск предложит повышение прав; отказ сохранит текущую сеть.",
                    warning = true,
                )
            }
            val hotApply = state.capabilities.preservesTun && state.capabilities.atomicRules && state.capabilities.independentExits
            if (!hotApply) {
                ExpertMessage(
                    "Подтверждение обработчика требуется при запуске",
                    "Перед созданием TUN LerNET проверит независимые выходы и атомарную смену схемы. " +
                        "Если обработчик их не поддерживает, запуск будет отклонён с объяснением.",
                    warning = true,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = state.phase in setOf(ExpertPhase.RUNNING, ExpertPhase.APPLYING)
                Button(
                    onClick = { if (running) confirm(ExpertIntent.Stop) else onIntent(ExpertIntent.Start) },
                    enabled = running || (!state.busy && state.phase in setOf(ExpertPhase.STOPPED, ExpertPhase.FAILED)),
                ) { Text(if (running) "Остановить TUN" else "Включить управление") }
                OutlinedButton(onClick = openRoutes) { Text("Настроить схему") }
                TextButton(onClick = { onIntent(ExpertIntent.Refresh) }, enabled = !state.busy) { Text("Обновить состояния") }
                if (state.phase == ExpertPhase.STARTING) {
                    OutlinedButton(onClick = { onIntent(ExpertIntent.Stop) }) {
                        Text("Отменить запуск")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ExpertPanel("Схема", Modifier.weight(1f)) {
                val count = state.draft.device.nodes.size
                Text("$count правил устройства", color = ExpertColors.text, fontSize = 22.sp)
                Text(
                    "Сохранена: ${state.saved.revision} · применена: ${state.appliedRevision?.toString() ?: "нет"}",
                    color = ExpertColors.muted
                )
                TextButton(onClick = {
                    onIntent(ExpertIntent.SelectScope(PolicyScope.Device))
                    openRoutes()
                }) { Text("Открыть дерево") }
            }
            ExpertPanel("Выходы", Modifier.weight(1f)) {
                Text("${state.exits.count { it.activeFlows > 0 }} используются", color = ExpertColors.text, fontSize = 22.sp)
                Text("${state.exits.size} выходов с подтверждённым состоянием", color = ExpertColors.muted)
                Text(
                    "Спящие выходы поднимаются по запросу, если для них задан холодный старт.",
                    color =
                    ExpertColors.muted,
                    fontSize = 13.sp
                )
            }
        }
        ExpertMessage(
            "Как читать состояния",
            "«Сохранена» означает запись на диск. «Применена» — подтверждение обработчика. " +
                "Изменение черновика не меняет активную сеть. Зелёное состояние появляется только после фактического подтверждения.",
        )
        ExpertPanel("Проверки связи", reducedMotion = state.reducedMotion) {
            val health = state.draft.health
            Text(
                "В черновике: случайный интервал ${healthSeconds(health.minimumIntervalMs)}–" +
                    "${healthSeconds(health.maximumIntervalMs)} с · ожидание ${healthSeconds(health.activeTimeoutMs)} с · " +
                    "неудач подряд: ${health.failedChecksBeforeRecovery}",
                color = ExpertColors.text,
            )
            if (state.phase in setOf(ExpertPhase.RUNNING, ExpertPhase.APPLYING)) {
                val active = state.applied?.health
                Text(
                    if (active == null) {
                        "Действующие значения не переданы обработчиком. Черновик не подтверждает активные настройки."
                    } else {
                        "Применена версия ${state.appliedRevision}: интервал ${healthSeconds(active.minimumIntervalMs)}–" +
                            "${healthSeconds(active.maximumIntervalMs)} с · ожидание ${healthSeconds(active.activeTimeoutMs)} с · " +
                            "неудач подряд: ${active.failedChecksBeforeRecovery}"
                    },
                    color = ExpertColors.muted,
                )
            }
            Text(
                "Проверяем путь через выход; серия неудач запускает восстановление. Интервал и время ожидания " +
                    "настраиваются отдельно от ручной проверки сервера.",
                color = ExpertColors.muted,
            )
            OutlinedButton(onClick = { healthSettings = true }, enabled = !state.busy) { Text("Настроить проверки связи") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onIntent(ExpertIntent.Import) }, enabled = !state.busy) { Text("Импортировать схему") }
            OutlinedButton(onClick = { onIntent(ExpertIntent.Export) }, enabled = !state.busy) { Text("Экспортировать") }
        }
    }
    if (healthSettings) ExpertHealthSettings(state, onIntent) { healthSettings = false }
}

@Composable
private fun DraftActions(
    state: ExpertUiState,
    validation: List<String>,
    onIntent: (ExpertIntent) -> Unit,
    confirm: (ExpertIntent) -> Unit,
) {
    var allErrors by remember { mutableStateOf(false) }
    ExpertPanel(if (state.hasDraftChanges) "Черновик" else "Сохранённая схема ещё не применена") {
        if (validation.isNotEmpty()) {
            ExpertMessage(
                "Исправьте схему перед сохранением", validation.first(), error = true, bodyMaxLines = 2,
            )
        }
        if (validation.isNotEmpty()) TextButton(onClick = { allErrors = true }) { Text("Показать замечания · ${validation.size}") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.hasDraftChanges) {
                Button(onClick = { onIntent(ExpertIntent.SaveDraft) }, enabled = validation.isEmpty() && !state.busy) {
                    Text("Сохранить · Ctrl+S")
                }
                OutlinedButton(onClick = { confirm(ExpertIntent.DiscardDraft) }, enabled = !state.busy) { Text("Отменить изменения") }
            } else if (state.phase == ExpertPhase.RUNNING) {
                Button(
                    onClick = { confirm(ExpertIntent.ApplySaved) },
                    enabled = !state.busy &&
                        state.capabilities.atomicRules &&
                        state.capabilities.preservesTun &&
                        state.capabilities.independentExits,
                ) { Text("Применить к работающему TUN") }
            }
            Text("Активна версия ${state.appliedRevision?.toString() ?: "не подтверждена"}", color = ExpertColors.muted, fontSize = 12.sp)
        }
        if (!state.hasDraftChanges &&
            state.phase == ExpertPhase.RUNNING &&
            (!state.capabilities.atomicRules || !state.capabilities.preservesTun || !state.capabilities.independentExits)
        ) {
            Text("Обработчик не подтвердил безопасную смену схемы на работающем TUN.", color = ExpertColors.amber, fontSize = 12.sp)
        }
        if (state.appliedRevision != null) {
            TextButton(
                onClick = { confirm(ExpertIntent.RestoreAppliedToDraft) }, enabled = !state.busy,
            ) { Text("Вернуть применённую версию в черновик") }
        }
    }
    if (allErrors) {
        ExpertModal("Проверка схемы", { allErrors = false }, { allErrors = false }, true, "Закрыть") {
            validation.forEachIndexed { index, error ->
                ExpertMessage("Замечание ${index + 1}", error, error = true)
            }
        }
    }
}

@Composable
private fun ExpertProtectionPage(
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    modifier: Modifier,
    confirm: (ExpertIntent) -> Unit,
) {
    ExpertScrollableColumn(modifier) {
        ExpertPanel("Защита при отказе") {
            ProtectionRow(
                "Недоступен отдельный выход", state.protection.exitFailureEnforced,
                "Для трафика, совпавшего с защищённой веткой, прямой запасной путь запрещён."
            )
            ProtectionRow(
                "DNS ядра следует правилам", state.protection.dnsFollowsPolicy,
                "Это DNS, который обрабатывает ядро. Собственный DoH приложения — отдельное шифрованное соединение, " +
                    "для него нужны правила маршрутизации самого приложения или его адреса."
            )
            ProtectionRow("IPv4 и IPv6", state.protection.ipv6Covered, "Правила должны действовать для обоих семейств адресов.")
            ProtectionRow(
                "Остановился TUN", state.protection.systemGuardEnforced,
                "Требуется независимая защита Windows, которая переживает процесс LerNET."
            )
            ExpertMessage("Границы гарантии", state.protection.explanation, warning = !state.protection.systemGuardEnforced)
            ExpertMessage(
                "Когда Windows меняет путь",
                "Правила схемы действуют на трафик, захваченный TUN. Чужой VPN может добавить более приоритетный маршрут. " +
                    "Без системной защиты часть трафика успеет пройти мимо TUN до проверки маршрутов раз в две секунды. " +
                    "Включите системную защиту всего устройства, если обход TUN должен блокироваться сразу.",
                warning = !state.protection.systemGuardEnforced,
            )
            ExpertMessage(
                "Другой VPN и корпоративная сеть",
                "Защита всего устройства может заблокировать внешние соединения другого VPN-клиента. " +
                    "Тогда его корпоративный адаптер останется в списке, но доступ через него пропадёт. " +
                    "Совместный доступ через уже подключённый VPN проверяйте без этой дополнительной защиты; " +
                    "защищённые ветки внутри TUN продолжают запрещать прямой запасной путь.",
                warning = true,
            )
            ExpertMessage(
                "Локальные связи между программами",
                "Защита всего устройства блокирует и обычные соединения с localhost. " +
                    "Сохранён только собственный канал управления LerNET. Программы с локальным сервером " +
                    "или отдельным прокси могут потерять связь до отключения этой защиты.",
                warning = true,
            )
            DomainRecognitionQualification()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.protection.systemGuardEnforced) {
                    Button(onClick = { onIntent(ExpertIntent.EnableSystemGuard) }, enabled = !state.busy) {
                        Text("Настроить системную защиту")
                    }
                } else {
                    OutlinedButton(onClick = { confirm(ExpertIntent.DisableSystemGuard) }, enabled = !state.busy) {
                        Text("Отключить защиту")
                    }
                }
                TextButton(onClick = { onIntent(ExpertIntent.RecoverSystemGuard) }, enabled = !state.busy) {
                    Text("Проверить и восстановить защиту")
                }
            }
        }
    }
}

@Composable
private fun ProtectionRow(title: String, enforced: Boolean, explanation: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ExpertColors.text, fontWeight = FontWeight.Medium)
            Text(explanation, color = ExpertColors.muted, fontSize = 13.sp)
        }
        ExpertTag(if (enforced) "Подтверждено" else "Не подтверждено", if (enforced) ExpertColors.green else ExpertColors.amber)
    }
    Spacer(Modifier.height(2.dp))
}
