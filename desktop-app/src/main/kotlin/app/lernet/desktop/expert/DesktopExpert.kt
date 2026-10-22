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
import app.lernet.desktop.WorkspaceNavigationHeader
import app.lernet.desktop.WorkspaceNavigationItem
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip

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
    frameAnimateIn: Boolean = true,
    configLibrary: (@Composable (Modifier) -> Unit)? = null,
    appSettings: (@Composable (Modifier) -> Unit)? = null,
) {
    // G3: the section is remembered for the session (mode switches included); a new launch opens the pult.
    var pageName by rememberSaveable { mutableStateOf(ExpertPage.OVERVIEW.name) }
    val page = ExpertPage.valueOf(pageName)
    val openPage: (ExpertPage) -> Unit = { pageName = it.name }
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
        // m3 / N1, N6: the Expert sections live in the left column (NavigationView), not in a strip at the bottom.
        DesktopWorkspaceSidebar({ onIntent(ExpertIntent.OpenNetworkObservation) }) { // the mode is in the VPN | Эксперт switch above; «LerNET · Эк…» was cut
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
        app.lernet.ui.controls.ExpertFramedBox(
            app.lernet.ui.controls.ExpertFrameSpec.Windows,
            Modifier.weight(1f).fillMaxHeight(),
            lit = if (state.phase == ExpertPhase.RUNNING) 1f else 0.35f,
            animateIn = frameAnimateIn && !state.reducedMotion,
        ) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    // G4 (as in VPN mode and on the phone): the active item in the left column names the page,
                    // no second title above the content.
                    // No subtitles: the phone has none; only the Windows-only app settings page explains itself.
                    val subtitle = when (page) {
                        ExpertPage.APP_SETTINGS -> "Общие настройки приложения: действуют и в режиме VPN"
                        else -> null
                    }
                    subtitle?.let { Text(it, color = ExpertColors.muted) }
                }
                ExpertWorkspaceMenu(state, onIntent)
            }
            state.error?.let { ExpertMessage("Операция не завершена", it, error = true) }
            state.notice?.let { ExpertMessage("Состояние сети", it) }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Ждём ответа ядра…", color = ExpertColors.muted)
                }
            }
            pageStates.SaveableStateProvider(page.name) {
                when (page) {
                    ExpertPage.OVERVIEW -> ExpertLiveOverview(
                        state, onIntent, Modifier.weight(1f), { confirmation = it }, openPage = openPage,
                    )
                    ExpertPage.ROUTES -> ExpertRoutes(state, onIntent, Modifier.weight(1f))
                    ExpertPage.PREVIEW -> ExpertRoutePreview(
                        state, Modifier.weight(1f), onIntent, { openPage(ExpertPage.ROUTES) }, recordedFlow = recordedFlow,
                        showRecordedPath = recordedPathOnly, requestToken = previewRequest,
                    )
                    ExpertPage.EXITS -> ExpertExits(state, onIntent, Modifier.weight(1f)) { openPage(ExpertPage.ROUTES) }
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
            if (state.hasDraftChanges || (state.phase == ExpertPhase.RUNNING && state.appliedRevision != null && state.hasUnappliedChanges)) {
                DraftActions(state, validation, onIntent) { confirmation = it }
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
            "Применить сохранённую схему?",
            "После подтверждения новые соединения будут использовать сохранённую схему без перезапуска туннеля. " +
                "Если ответ ядра потеряется, состояние правил нельзя считать известным: " +
                "приложение покажет ошибку и проверит состояние либо выключит туннель.",
            "Применить", {
                confirmation = null
                onIntent(ExpertIntent.ApplySaved)
            }, { confirmation = null },
        )
        ExpertIntent.RestoreAppliedToDraft -> ExpertConfirm(
            "Вернуть применённую схему в редактор?",
            "Текущий черновик будет заменён схемой, которая сейчас применяется к сети. " +
                "Сохранённая версия и работающий туннель не изменятся. Затем схему можно отредактировать или сохранить.",
            "Вернуть в черновик", {
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
private fun ExpertWorkspaceMenu(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Настройки Эксперта" }) {
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
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (validation.isNotEmpty()) {
                ExpertMessage(
                    "Исправь схему перед сохранением", validation.first(), error = true, bodyMaxLines = 2,
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
                    ) { Text("Применить сейчас") }
                }
                val schemaStatus = app.lernet.engine.policy.ExpertPult.schemaLine(
                    state.pultStatus, unsavedDraft = state.hasDraftChanges,
                    unapplied = state.appliedRevision != null && state.hasUnappliedChanges,
                    applied = state.appliedRevision != null,
                )
                Text(schemaStatus, color = ExpertColors.muted, fontSize = 12.sp)
            }
            if (!state.hasDraftChanges &&
                state.phase == ExpertPhase.RUNNING &&
                (!state.capabilities.atomicRules || !state.capabilities.preservesTun || !state.capabilities.independentExits)
            ) {
                Text("На ходу эту схему не сменить. Выключи и включи — применится.", color = ExpertColors.amber, fontSize = 12.sp)
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
