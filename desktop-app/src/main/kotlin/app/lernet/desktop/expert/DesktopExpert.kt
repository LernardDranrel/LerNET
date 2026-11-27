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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import app.lernet.desktop.WorkspaceNavigationHeader
import app.lernet.desktop.WorkspaceNavigationItem
import app.lernet.ui.controls.ExpertTemplatePicker
import app.lernet.ui.controls.TemplateAction
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds

internal enum class ExpertPage(val title: String) {
    OVERVIEW("Пульт"),
    ROUTES("Схема"),
    EXITS("Конфиги"),
    PREVIEW("Проверка пути"),
    EVENTS("События"),
    PROTECTION("Защита и DNS"),
    LIBRARY("Конфиги и папки"),
    APP_SETTINGS("Настройки приложения"),
}

/** The five Expert sections, the same set and order as the phone's bottom menu (m3, m4). */
internal val ExpertNavPages = listOf(ExpertPage.OVERVIEW, ExpertPage.ROUTES, ExpertPage.EXITS, ExpertPage.PREVIEW, ExpertPage.EVENTS)

/** The controller alone starts networking and acknowledges applied revisions. */
@Composable
fun DesktopExpert(
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    modifier: Modifier = Modifier,
    configLibrary: (@Composable (Modifier) -> Unit)? = null,
    appSettings: (@Composable (Modifier) -> Unit)? = null,
    /** Global mode switch and tunnel status provided by the desktop window. */
    topChrome: @Composable () -> Unit = {},
    onCopyProfile: ((String) -> Unit)? = null,
    onRenameFolder: ((String, String) -> Unit)? = null,
    onDeleteFolder: ((String) -> Unit)? = null,
    onRefreshSubscription: ((String) -> Unit)? = null,
    onTemplateChange: suspend (TemplateAction) -> String? = { "Шаблоны доступны в открытой рабочей области." },
) {
    // G3: the section is remembered for the session (mode switches included); a new launch opens the pult.
    var pageName by rememberSaveable { mutableStateOf(ExpertPage.OVERVIEW.name) }
    val page = ExpertPage.valueOf(pageName)
    val openPage: (ExpertPage) -> Unit = { pageName = it.name }
    val pageStates = rememberSaveableStateHolder()
    var confirmation by remember(state.selectedTemplateId) { mutableStateOf<ExpertIntent?>(null) }
    var recordedFlow by remember { mutableStateOf<ExpertConnection?>(null) }
    var recordedPathOnly by remember { mutableStateOf(false) }
    var previewRequest by remember { mutableStateOf(0L) }
    var requestedDeviceRule by remember(state.selectedTemplateId) { mutableStateOf<String?>(null) }
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
        // m3 / N1, N6: sections in the left column; flush to the window top (same nesting as VPN DesktopScreen).
        if (page != ExpertPage.ROUTES) DesktopWorkspaceSidebar({ onIntent(ExpertIntent.OpenNetworkObservation) }, expertBrand = true) {
            Column(Modifier.semantics { contentDescription = "Разделы Эксперта" }) {
                ExpertNavPages.forEach { item ->
                    WorkspaceNavigationItem(item.title, page == item, { openPage(item) }, badge = navBadge(item, state))
                }
            }
            Spacer(Modifier.height(10.dp))
            WorkspaceNavigationHeader("Настройки")
            WorkspaceNavigationItem(ExpertPage.PROTECTION.title, page == ExpertPage.PROTECTION, { openPage(ExpertPage.PROTECTION) })
            if (configLibrary != null) {
                WorkspaceNavigationItem(ExpertPage.LIBRARY.title, page == ExpertPage.LIBRARY, { openPage(ExpertPage.LIBRARY) })
            }
            if (appSettings != null) {
                WorkspaceNavigationItem(ExpertPage.APP_SETTINGS.title, page == ExpertPage.APP_SETTINGS, { openPage(ExpertPage.APP_SETTINGS) })
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val smallHeader = maxWidth < 735.dp
                Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF0D1420)).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page == ExpertPage.ROUTES) {
                        ExpertSectionMenu(page, openPage, configLibrary != null, appSettings != null, smallHeader) {
                            onIntent(ExpertIntent.OpenNetworkObservation)
                        }
                    }
                    Box(Modifier.weight(1f).clipToBounds()) { topChrome() }
                    if (state.workspaceReady) {
                        val choices = remember(state.templateChoices) { state.templateChoices.map { it.id to it.name } }
                        ExpertTemplatePicker(choices, state.selectedTemplateId, onChange = onTemplateChange, compact = true,
                            enabled = !state.busy && state.phase !in setOf(ExpertPhase.STARTING, ExpertPhase.STOPPING),
                            modifier = Modifier.widthIn(max = if (smallHeader) 130.dp else 165.dp), protectedId = state.appliedTemplateId)
                    }
                    ExpertWorkspaceMenu(state, onIntent, smallHeader) { confirmation = ExpertIntent.RestoreAppliedToDraft }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = ExpertColors.border)
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page != ExpertPage.ROUTES && state.workspaceReady) {
                    Text(page.title, color = ExpertColors.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                }
                if (state.workspaceReady && (state.hasDraftChanges ||
                        (state.phase == ExpertPhase.RUNNING && state.appliedRevision != null && state.hasUnappliedChanges))) {
                    DraftActions(state, validation, onIntent) { confirmation = it }
                }
                if (page == ExpertPage.APP_SETTINGS) {
                    Text("Общие настройки VPN и Эксперта", color = ExpertColors.muted)
                }
                // One banner only: while the workspace gate is up, workspaceError owns the message
                // (bbda1d1 wrote the same failure into both error and workspaceError → dual banners + empty gap).
                if (state.workspaceReady) {
                    state.error?.let { ExpertMessage("Операция не завершена", it, error = true) }
                }
                state.notice?.let { ExpertMessage("Состояние сети", it) }
                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Ждём ответа ядра…", color = ExpertColors.muted)
                    }
                }
                if (!state.workspaceReady) {
                    // Top-aligned: Center + weight(1f) left a huge empty hole above the only banner (live screenshot).
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (state.workspaceError == null) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                Text("Читаем настройки и конфиги…", color = ExpertColors.muted)
                            } else {
                                ExpertMessage("Рабочая область не загрузилась", state.workspaceError, error = true)
                                OutlinedButton(onClick = { onIntent(ExpertIntent.Refresh) }) { Text("Повторить") }
                            }
                        }
                    }
                } else pageStates.SaveableStateProvider("${state.selectedTemplateId}/${page.name}") {
                    when (page) {
                        ExpertPage.OVERVIEW -> ExpertLiveOverview(
                            state, onIntent, Modifier.weight(1f), { confirmation = it }, openPage = openPage,
                            editRule = { id -> requestedDeviceRule = id; openPage(ExpertPage.ROUTES) },
                        )
                        ExpertPage.ROUTES -> ExpertRoutes(state, onIntent, Modifier.weight(1f),
                            requestedDeviceRule = requestedDeviceRule, onRuleOpened = { requestedDeviceRule = null })
                        ExpertPage.PREVIEW -> ExpertRoutePreview(
                            state, Modifier.weight(1f), onIntent, { openPage(ExpertPage.ROUTES) }, recordedFlow = recordedFlow,
                            showRecordedPath = recordedPathOnly, requestToken = previewRequest,
                        )
                        ExpertPage.EXITS -> ExpertExits(
                            state, onIntent, Modifier.weight(1f),
                            openRoutes = { openPage(ExpertPage.ROUTES) },
                            onCopyProfile = onCopyProfile,
                            onRenameFolder = onRenameFolder,
                            onDeleteFolder = onDeleteFolder,
                            onRefreshSubscription = onRefreshSubscription,
                        )
                        ExpertPage.EVENTS -> ExpertEvents(
                            state, onIntent, Modifier.weight(1f),
                            simulate = {
                                recordedFlow = it
                                recordedPathOnly = false
                                previewRequest++
                                openPage(ExpertPage.PREVIEW)
                            },
                            showPath = {
                                recordedFlow = it
                                recordedPathOnly = true
                                previewRequest++
                                openPage(ExpertPage.PREVIEW)
                            },
                        )
                        ExpertPage.PROTECTION -> ExpertProtectionPage(state, onIntent, Modifier.weight(1f)) { confirmation = it }
                        ExpertPage.LIBRARY -> configLibrary?.invoke(Modifier.weight(1f).fillMaxWidth())
                        ExpertPage.APP_SETTINGS -> appSettings?.invoke(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
    when (confirmation) {
        ExpertIntent.Stop -> ExpertConfirm(
            "Остановить управление сетью?",
            "Туннель будет выключен. Правила внутри него перестанут действовать. " +
                if (state.protection.systemGuardEnforced) {
                    "Системная защита останется включённой и заблокирует Интернет. " +
                        "Чтобы вернуть обычную сеть, отдельно отключи защиту."
                } else {
                    "Защита вне туннеля не подтверждена: после остановки трафик может идти напрямую. " +
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
            "Применить шаблон «${state.templateChoices.first { it.id == state.selectedTemplateId }.name}»?",
            "Когда ядро подтвердит переключение, новые соединения пойдут по сохранённым правилам этого шаблона.",
            "Применить", {
                confirmation = null
                onIntent(ExpertIntent.ApplySaved)
            }, { confirmation = null },
        )
        ExpertIntent.RestoreAppliedToDraft -> ExpertConfirm(
            "Заменить черновик шаблона?",
            "В редакторе шаблона «${state.templateChoices.firstOrNull { it.id == state.selectedTemplateId }?.name.orEmpty()}» " +
                "вместо текущего черновика откроется копия действующей схемы. Для записи в шаблон потребуется «Сохранить».",
            "Заменить черновик", {
                confirmation = null
                onIntent(ExpertIntent.RestoreAppliedToDraft)
            }, { confirmation = null },
        )
        ExpertIntent.DisableSystemGuard -> ExpertConfirm(
            "Отключить системную защиту?",
            "Защита больше не сможет блокировать обход после выключения туннеля. Защищённые ветки внутри работающего туннеля " +
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
private fun ExpertSectionMenu(page: ExpertPage, go: (ExpertPage) -> Unit, library: Boolean, settings: Boolean, compact: Boolean, network: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(7.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = true }
            .semantics { contentDescription = "Разделы Эксперта" }.padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(ExpertColors.blue, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text("L", color = ExpertColors.background, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
            if (!compact) Text("LerNET", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = ExpertColors.text)
            Text("Разделы ▾", fontSize = 12.sp, color = ExpertColors.muted)
        }
        DropdownMenu(expanded, { expanded = false }) {
            val pages = ExpertNavPages + ExpertPage.PROTECTION + listOfNotNull(ExpertPage.LIBRARY.takeIf { library },
                ExpertPage.APP_SETTINGS.takeIf { settings })
            pages.forEach { item ->
                DropdownMenuItem(text = { Text(item.title) }, trailingIcon = { if (page == item) Text("✓") },
                    onClick = { expanded = false; go(item) })
            }
            androidx.compose.material3.HorizontalDivider()
            DropdownMenuItem(text = { Text("Анализ сети") }, onClick = { expanded = false; network() })
        }
    }
}

@Composable
private fun ExpertWorkspaceMenu(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, compact: Boolean, restoreDraft: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }) {
            if (compact) Text("⋯", Modifier.semantics { contentDescription = "Инструменты" }) else Text("Инструменты ▾")
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (state.appliedRevision != null && (state.hasDraftChanges || state.hasUnappliedChanges)) {
                DropdownMenuItem(
                    text = { Text("Скопировать действующую схему в черновик…") },
                    onClick = { menu = false; restoreDraft() }, enabled = !state.busy,
                )
            }
            DropdownMenuItem(
                text = { Text("Очистить завершённую историю") },
                onClick = {
                    menu = false
                    onIntent(ExpertIntent.ClearConnectionHistory)
                }, enabled = !state.busy
            )
            DropdownMenuItem(
                text = { Text("Импортировать рабочую схему") },
                onClick = {
                    menu = false
                    onIntent(ExpertIntent.Import)
                }, enabled = !state.busy
            )
            DropdownMenuItem(
                text = { Text("Сохранить рабочую схему в файл") },
                onClick = {
                    menu = false
                    onIntent(ExpertIntent.Export)
                }, enabled = !state.busy
            )
        }
    }
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (validation.isNotEmpty()) {
                ExpertMessage(
                    "Исправь схему перед сохранением", validation.first(), error = true, bodyMaxLines = 2,
                )
            }
            if (validation.isNotEmpty()) TextButton(onClick = { allErrors = true }) { Text("Показать замечания · ${validation.size}") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                val liveTemplate = state.templateChoices.find { it.id == state.appliedTemplateId }
                val status = if (liveTemplate == null && state.applied != null && state.hasUnappliedChanges) {
                    "В сети — предыдущая схема"
                } else if (liveTemplate != null && liveTemplate.id != state.selectedTemplateId) {
                    "В сети — ${liveTemplate.name}"
                } else app.lernet.engine.policy.ExpertPult.schemaLine(
                    state.pultStatus, unsavedDraft = state.hasDraftChanges,
                    unapplied = state.appliedRevision != null && state.hasUnappliedChanges,
                    applied = state.appliedRevision != null,
                )
                Text(status, color = ExpertColors.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
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
                    ) { Text("Применить сейчас") }
                }
            }
            if (!state.hasDraftChanges &&
                state.phase == ExpertPhase.RUNNING &&
                (!state.capabilities.atomicRules || !state.capabilities.preservesTun || !state.capabilities.independentExits)
            ) {
                Text("На ходу эту схему не сменить. Выключи и включи — применится.", color = ExpertColors.amber, fontSize = 12.sp)
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
    var dns by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf(false) }
    ExpertScrollableColumn(modifier) {
        // Audit X7: one page «Защита и DNS» — DNS and the connection checks are rows here, not items in ⋮.
        val words = app.lernet.engine.text.ExpertWords
        ExpertPanel("") {
            ExpertLinkRow(words.DNS_ROW, words.DNS_ROW_HINT, !state.busy) { dns = true }
            ExpertLinkRow(words.HEALTH_ROW, words.HEALTH_ROW_HINT, !state.busy) { health = true }
        }
        ExpertPanel(words.SAFETY_TITLE) {
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
                "Туннель выключился", state.protection.systemGuardEnforced,
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
    if (dns) ExpertDnsSettings(state, onIntent) { dns = false }
    if (health) ExpertHealthSettings(state, onIntent) { health = false }
    if (details) {
        ExpertModal("Границы системной защиты", { details = false }, { details = false }, true, "Понятно", showCancel = false) {
            ExpertMessage(
                "Когда Windows меняет путь",
                "Правила схемы действуют на трафик, который идёт через туннель. Чужой VPN может добавить более приоритетный маршрут. " +
                    "Без системной защиты часть трафика может пройти мимо туннеля: маршруты проверяются раз в две секунды. " +
                    "Включи системную защиту всего устройства, если обход туннеля должен блокироваться сразу.",
                warning = !state.protection.systemGuardEnforced,
            )
            ExpertMessage(
                "Другой VPN и корпоративная сеть",
                "Защита всего устройства может заблокировать внешние соединения другого VPN-клиента. " +
                    "Тогда его корпоративный адаптер останется в списке, но доступ через него пропадёт. " +
                    "Совместный доступ через уже подключённый VPN проверяй без этой дополнительной защиты; " +
                    "защищённые ветки внутри туннеля продолжают запрещать прямой запасной путь.",
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

private fun navBadge(page: ExpertPage, state: ExpertUiState): String? = when (page) {
    ExpertPage.EXITS -> state.exits.takeIf { it.isNotEmpty() }?.let { exits ->
        "${exits.count { it.phase == app.lernet.engine.policy.ExitPhase.READY }}/${exits.size}"
    }
    ExpertPage.EVENTS -> state.connections.count { it.active == true }.takeIf { it > 0 }?.toString()
    else -> null
}

@Composable
private fun ExpertLinkRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ExpertColors.text, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = ExpertColors.muted, fontSize = 13.sp)
        }
        Text("›", color = ExpertColors.muted, fontSize = 20.sp)
    }
}
