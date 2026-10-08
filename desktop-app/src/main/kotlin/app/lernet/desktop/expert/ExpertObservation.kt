package app.lernet.desktop.expert

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.engine.policy.ExitPhase
import app.lernet.routing.policy.NetworkPolicy

@Composable
internal fun ExpertExits(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, modifier: Modifier, openRoutes: () -> Unit = {}) {
    var selected by remember { mutableStateOf<String?>(null) }
    var sleepingOnly by remember { mutableStateOf(false) }
    var editingChannel by remember { mutableStateOf<String?>(null) }
    var editingProfile by remember { mutableStateOf<String?>(null) }
    var editingFolder by remember { mutableStateOf<String?>(null) }
    var pendingPolicy by remember { mutableStateOf<ExpertDraftSubmission?>(null) }
    var externalEditor by remember { mutableStateOf(false) }
    var editingExternal by remember { mutableStateOf<ExpertExternalProfile?>(null) }
    var externalDetails by remember { mutableStateOf<ExpertExternalProfile?>(null) }
    var importedInterfaceDetails by remember { mutableStateOf<ExpertProfile?>(null) }
    fun closeEditors() {
        editingChannel = null
        editingProfile = null
        editingFolder = null
    }
    fun commit(policy: NetworkPolicy) {
        if (policy == state.draft && state.error == null) {
            closeEditors()
            return
        }
        pendingPolicy = ExpertDraftSubmission.capture(policy, state)
        onIntent(ExpertIntent.EditPolicy(policy))
    }
    LaunchedEffect(state.draft, state.error, state.events.lastOrNull()?.id, pendingPolicy) {
        when (pendingPolicy?.response(state)) {
            ExpertDraftResponse.CONFIRMED -> {
                closeEditors()
                pendingPolicy = null
            }
            ExpertDraftResponse.FAILED -> pendingPolicy = null
            ExpertDraftResponse.WAITING, null -> Unit
        }
    }
    val exits = state.exits.filter { !sleepingOnly || it.phase == ExitPhase.SLEEPING }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Независимые выходы", Modifier.weight(1f), color = ExpertColors.text, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            FilterChip(sleepingOnly, { sleepingOnly = !sleepingOnly }, label = { Text("Спящие") })
            OutlinedButton(
                onClick = {
                    editingExternal = null
                    externalEditor = true
                },
                enabled = !state.busy && state.externalSavePending == null
            ) { Text("Добавить внешний выход") }
            OutlinedButton(onClick = { onIntent(ExpertIntent.Refresh) }, enabled = !state.busy) { Text("Обновить") }
        }
        Text(
            "Сбой выхода не означает остановку общего TUN. Проверки состояния не пробуждают холодные выходы.",
            color = ExpertColors.muted, fontSize = 13.sp
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (exits.isEmpty()) {
                item {
                    ExpertEmpty(
                        if (state.exits.isEmpty()) "Выходы пока не созданы" else "Нет спящих выходов",
                        if (state.phase == ExpertPhase.RUNNING) {
                            "Настройте цель в схеме. Здесь появятся подтверждённые состояния обработчика."
                        } else {
                            "Состояния появятся после запуска Экспертного режима."
                        }
                    )
                }
            }
            items(exits, key = { it.id }) { exit ->
                ExpertPanel(
                    exit.name, Modifier.fillMaxWidth().clickable { selected = exit.id }, reducedMotion = state.reducedMotion,
                    trailing = { ExpertTag(exitDisplayName(exit, state), exitDisplayColor(exit, state)) }
                ) {
                    Text(exit.targetDescription, color = ExpertColors.muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${exit.activeFlows} соединений", color = ExpertColors.text)
                        if (exit.pendingFlows > 0) ExpertTag("${exit.pendingFlows} ожидают", ExpertColors.amber)
                        Text(
                            exit.latencyMs?.let { "$it мс через выход" } ?: "Задержка не измерена",
                            color =
                            ExpertColors.muted,
                            fontSize = 12.sp
                        )
                        if (exit.coldStart) ExpertTag("Холодный старт", ExpertColors.muted)
                    }
                    exit.reason?.let { Text(it, color = ExpertColors.muted, fontSize = 13.sp) }
                    ExitTransportQualification(exit, state)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selected = exit.id }) { Text("Подробнее") }
                        if (exit.canWake) {
                            OutlinedButton(
                                onClick = { onIntent(ExpertIntent.WakeExit(exit.id)) },
                                enabled = !state.busy
                            ) { Text("Разбудить") }
                        }
                        if (exit.canSleep) {
                            OutlinedButton(
                                onClick = { onIntent(ExpertIntent.SleepExit(exit.id)) },
                                enabled = !state.busy
                            ) { Text("Усыпить") }
                        }
                    }
                }
            }
            externalProfileDefinitions(
                state, { externalDetails = it }, {
                    editingExternal = it
                    externalEditor = true
                },
                { importedInterfaceDetails = it }
            )
        }
    }
    if (externalEditor) {
        ExpertExternalExitEditor(editingExternal, state, onIntent, {
            externalEditor = false
            editingExternal = null
        })
    }
    externalDetails?.let { profile ->
        ExpertExternalExitDetails(profile, state, { externalDetails = null }, {
            editingExternal = profile
            externalDetails = null
            externalEditor = true
        }, { scope ->
            onIntent(ExpertIntent.SelectScope(scope))
            externalDetails = null
            openRoutes()
        })
    }
    importedInterfaceDetails?.let { profile ->
        ExpertImportedInterfaceDetails(profile, state, { importedInterfaceDetails = null }, { scope ->
            onIntent(ExpertIntent.SelectScope(scope))
            importedInterfaceDetails = null
            openRoutes()
        })
    }
    state.exits.firstOrNull { it.id == selected }?.let { exit ->
        ExpertModal("Выход: ${exit.name}", { selected = null }, { selected = null }, true, "Закрыть", showCancel = false) {
            ExpertTag(exitDisplayName(exit, state), exitDisplayColor(exit, state))
            ReadingRow("Цель", exit.targetDescription)
            exit.profileId?.let { id ->
                val external = state.externalProfiles.firstOrNull { it.id == id }
                external?.let { profile ->
                    OutlinedButton(onClick = {
                        selected = null
                        externalDetails = profile
                    }) { Text("Настройки внешнего профиля") }
                }
                if (external == null) {
                    state.profiles.firstOrNull { it.id == id && it.interfaceBinding != null }?.let { profile ->
                        OutlinedButton(onClick = {
                            selected = null
                            importedInterfaceDetails = profile
                        }) {
                            Text("Сохранённая привязка интерфейса")
                        }
                    }
                }
            }
            ReadingRow("Проверка", exit.lastCheck ?: "Подтверждённой проверки ещё нет")
            ReadingRow("Задержка через выход", exit.latencyMs?.let { "$it мс" } ?: "Нет замера")
            ReadingRow("Используется", "${exit.activeFlows} активных · ${exit.pendingFlows} в очереди")
            ReadingRow(
                "Сон",
                if (exit.coldStart) {
                    exit.idleRemainingMs?.let { "Через ${it / 1000} секунд тишины" }
                        ?: "Холодный старт включён"
                } else {
                    "Выход поддерживается активным"
                }
            )
            ExpertMessage("Почему такое состояние", exit.reason ?: "Обработчик ещё не передал подробную причину.")
            ExitTransportQualification(exit, state)
            val channel = exit.channelId?.let { id -> state.draft.channels.firstOrNull { it.id == id } }
            when {
                channel != null -> OutlinedButton(onClick = { editingChannel = channel.id }) {
                    Text("Настроить холодный старт и сон канала")
                }
                exit.folderId != null -> OutlinedButton(onClick = { editingFolder = exit.folderId }) {
                    Text("Настроить холодный старт папки")
                }
                exit.profileId != null -> OutlinedButton(onClick = { editingProfile = exit.profileId }) {
                    Text("Настроить холодный старт профиля")
                }
            }
            val nodes = (listOf(state.draft.device) + state.draft.trees).flatMap { it.nodes }.filter { node ->
                val target = node.target
                (exit.channelId != null && target is app.lernet.routing.policy.PolicyTarget.Channel && target.id == exit.channelId) ||
                    (exit.profileId != null && target is app.lernet.routing.policy.PolicyTarget.Profile && target.id == exit.profileId)
            }
            Text("Ветки черновика с этой целью", color = ExpertColors.text, fontWeight = FontWeight.SemiBold)
            if (nodes.isEmpty()) Text("Выход может быть выбран папкой или путём по умолчанию.", color = ExpertColors.muted)
            nodes.forEach { Text(it.title.ifBlank { "Без названия" }, color = ExpertColors.muted) }
            if (!interfaceBoundExit(exit, state)) {
                Text(
                    "Здоровье выхода определяется запросом через него. " +
                        "Доступный сервер с мёртвым каналом не считается рабочим выходом.",
                    color = ExpertColors.muted, fontSize = 13.sp
                )
            }
        }
    }
    editingChannel?.let { id ->
        state.draft.channels.firstOrNull { it.id == id }?.let { channel ->
            ExpertChannelEditor(channel, state, { editingChannel = null }, { changed ->
                commit(ExpertPolicyEditing.putChannel(state.draft, changed))
            }, { commit(ExpertPolicyEditing.deleteChannel(state.draft, channel.id)) }, pendingPolicy != null)
        }
    }
    editingProfile?.let { id ->
        ExpertProfileLifecycleEditor(id, state, { editingProfile = null }, pendingPolicy != null) { changed ->
            commit(state.draft.copy(profilePolicies = state.draft.profilePolicies.filterNot { it.profileId == id } + changed))
        }
    }
    editingFolder?.let { id ->
        ExpertFolderEditor(id, state, { editingFolder = null }, pendingPolicy != null) { changed ->
            commit(state.draft.copy(folderPolicies = state.draft.folderPolicies.filterNot { it.folderId == id } + changed))
        }
    }
}

@Composable
internal fun ExpertEvents(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Что делает LerNET", Modifier.weight(1f), color = ExpertColors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { onIntent(ExpertIntent.Refresh) }, enabled = !state.busy) { Text("Обновить") }
        }
        Text(
            "Применение схемы, пробуждение, отказ и смена выхода. Каждое событие сохраняет объяснение обработчика.",
            color = ExpertColors.muted, fontSize = 13.sp
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.events.isEmpty()) {
                item {
                    ExpertEmpty(
                        "Событий пока нет",
                        "После сетевых операций здесь появится последовательность решений."
                    )
                }
            }
            items(state.events, key = { it.id }) { event ->
                ExpertPanel(event.title, trailing = { Text(event.time, color = ExpertColors.muted, fontSize = 12.sp) }) {
                    Text(event.explanation, color = if (event.warning) ExpertColors.amber else ExpertColors.muted, fontSize = 13.sp)
                    if (event.warning) ExpertTag("Требует внимания", ExpertColors.amber)
                }
            }
        }
    }
}

@Composable
private fun ReadingRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = ExpertColors.muted, fontSize = 12.sp)
        Text(value, color = ExpertColors.text, fontSize = 14.sp)
    }
}
