@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.desktop.DesktopWorkspaceSidebar
import app.lernet.desktop.WorkspaceNavigationItem

private enum class ExpertPage(val title: String) {
    OVERVIEW("Обзор"),
    ROUTES("Схема"),
    PREVIEW("Симулятор"),
    EXITS("Наши подключения"),
    EVENTS("События"),
    PROTECTION("Настройки Expert")
}

/** The controller alone starts networking and acknowledges applied revisions. */
@Composable
fun DesktopExpert(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(ExpertPage.OVERVIEW) }
    val pageStates = rememberSaveableStateHolder()
    var confirmation by remember { mutableStateOf<ExpertIntent?>(null) }
    var recordedFlow by remember { mutableStateOf<ExpertConnection?>(null) }
    var recordedPathOnly by remember { mutableStateOf(false) }
    var previewRequest by remember { mutableStateOf(0L) }
    val validation = remember(state.draft, state.profiles, state.folders) { ExpertPolicyEditing.validation(state) }
    Row(
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
    ) {
        DesktopWorkspaceSidebar({ onIntent(ExpertIntent.OpenNetworkObservation) }) {
            ExpertPage.entries.forEach { item -> WorkspaceNavigationItem(item.title, page == item, { page = item }) }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (page == ExpertPage.OVERVIEW) "Управление сетью" else page.title,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        if (page == ExpertPage.ROUTES) {
                            "Правила устройства, профилей и папок"
                        } else {
                            "Экспертный режим · ваши правила для каждого соединения"
                        },
                        color = ExpertColors.muted
                    )
                }
                ExpertTag(phaseName(state.phase), if (state.phase == ExpertPhase.RUNNING) ExpertColors.green else ExpertColors.muted)
                ExpertWorkspaceMenu(state, onIntent)
            }
            state.error?.let { ExpertMessage("Операция не завершена", it, error = true) }
            state.notice?.let { ExpertMessage("Состояние сети", it) }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Ожидаем подтверждение обработчика…", color = ExpertColors.muted)
                }
            }
            pageStates.SaveableStateProvider(page.name) {
                when (page) {
                    ExpertPage.OVERVIEW -> ExpertLiveOverview(
                        state, onIntent, Modifier.weight(1f), { confirmation = it },
                        {
                            recordedFlow = it
                            recordedPathOnly = false
                            previewRequest++
                            page = ExpertPage.PREVIEW
                        },
                        {
                            recordedFlow = it
                            recordedPathOnly = true
                            previewRequest++
                            page = ExpertPage.PREVIEW
                        },
                    )
                    ExpertPage.ROUTES -> ExpertRoutes(state, onIntent, Modifier.weight(1f))
                    ExpertPage.PREVIEW -> ExpertRoutePreview(
                        state, Modifier.weight(1f), onIntent, { page = ExpertPage.ROUTES }, recordedFlow = recordedFlow,
                        showRecordedPath = recordedPathOnly, requestToken = previewRequest,
                    )
                    ExpertPage.EXITS -> ExpertExits(state, onIntent, Modifier.weight(1f)) { page = ExpertPage.ROUTES }
                    ExpertPage.EVENTS -> ExpertEvents(state, onIntent, Modifier.weight(1f))
                    ExpertPage.PROTECTION -> ExpertProtectionPage(state, onIntent, Modifier.weight(1f)) { confirmation = it }
                }
            }
            if (state.hasDraftChanges || (state.phase == ExpertPhase.RUNNING && state.hasUnappliedChanges)) {
                DraftActions(state, validation, onIntent) { confirmation = it }
            }
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
            "Отменить изменения?", "Черновик будет заменён последней сохранённой схемой. Работающая сеть не изменится.",
            "Отменить изменения", {
                confirmation = null
                onIntent(ExpertIntent.DiscardDraft)
            }, { confirmation = null },
        )
        ExpertIntent.ApplySaved -> ExpertConfirm(
            "Применить сохранённую схему?",
            "После подтверждения новые соединения будут использовать сохранённую схему на том же TUN. " +
                "Если ответ обработчика потеряется, состояние правил нельзя считать известным: " +
                "приложение покажет ошибку и проверит состояние либо остановит TUN.",
            "Применить", {
                confirmation = null
                onIntent(ExpertIntent.ApplySaved)
            }, { confirmation = null },
        )
        ExpertIntent.RestoreAppliedToDraft -> ExpertConfirm(
            "Вернуть применённую схему в редактор?",
            "Текущий черновик будет заменён схемой, которая сейчас применяется к сети. " +
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
        is ExpertIntent.EditPolicy, is ExpertIntent.UpdateLayout, is ExpertIntent.SelectScope,
        ExpertIntent.SaveDraft, ExpertIntent.Start,
        ExpertIntent.Refresh, ExpertIntent.ClearConnectionHistory, ExpertIntent.OpenNetworkObservation,
        ExpertIntent.Import, ExpertIntent.Export,
        ExpertIntent.ChooseExecutable, ExpertIntent.EnableSystemGuard, ExpertIntent.RecoverSystemGuard,
        ExpertIntent.RefreshInterfaces, is ExpertIntent.SaveExternalExit,
        is ExpertIntent.WakeExit, is ExpertIntent.SleepExit -> Unit
    }
}

@Composable
private fun ExpertWorkspaceMenu(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf(false) }
    var dns by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Настройки экспертного режима" }) {
            Text("⋮", fontSize = 24.sp)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Очистить завершённую историю") },
                onClick = {
                    menu = false
                    onIntent(ExpertIntent.ClearConnectionHistory)
                }, enabled = !state.busy
            )
            DropdownMenuItem(text = { Text("DNS Expert") }, onClick = {
                menu = false
                dns = true
            }, enabled = !state.busy)
            DropdownMenuItem(text = { Text("Проверки связи") }, onClick = {
                menu = false
                health = true
            }, enabled = !state.busy)
            DropdownMenuItem(
                text = { Text("Импортировать схему") },
                onClick = {
                    menu = false
                    onIntent(ExpertIntent.Import)
                }, enabled = !state.busy
            )
            DropdownMenuItem(
                text = { Text("Экспортировать схему") },
                onClick = {
                    menu = false
                    onIntent(ExpertIntent.Export)
                }, enabled = !state.busy
            )
        }
    }
    if (dns) ExpertDnsSettings(state, onIntent) { dns = false }
    if (health) ExpertHealthSettings(state, onIntent) { health = false }
}

@Composable
private fun DraftActions(
    state: ExpertUiState,
    validation: List<String>,
    onIntent: (ExpertIntent) -> Unit,
    confirm: (ExpertIntent) -> Unit,
) {
    var allErrors by remember { mutableStateOf(false) }
    Surface(color = ExpertColors.panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, ExpertColors.border)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                val schemaStatus = when {
                    state.hasDraftChanges || (state.appliedRevision != null && state.hasUnappliedChanges) -> "Есть неприменённые изменения"
                    state.appliedRevision != null -> "Схема применена"
                    else -> "Схема сохранена · туннель выключен"
                }
                Text(schemaStatus, color = ExpertColors.muted, fontSize = 12.sp)
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
                ) { Text("Вернуть работающую схему в редактор") }
            }
        }
    }
    if (allErrors) {
        ExpertModal("Проверка схемы", { allErrors = false }, { allErrors = false }, true, "Закрыть", showCancel = false) {
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
    var details by remember { mutableStateOf(false) }
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
            TextButton(onClick = { details = true }) { Text("Что защищено и какие есть ограничения") }
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
    if (details) {
        ExpertModal("Границы системной защиты", { details = false }, { details = false }, true, "Понятно", showCancel = false) {
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
