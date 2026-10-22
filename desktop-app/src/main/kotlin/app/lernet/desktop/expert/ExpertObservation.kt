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
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            FilterChip(sleepingOnly, { sleepingOnly = !sleepingOnly }, label = { Text(app.lernet.engine.text.ExpertWords.SLEEPING_ONLY) })
            OutlinedButton(
                onClick = {
                    editingExternal = null
                    externalEditor = true
                },
                enabled = !state.busy && state.externalSavePending == null
            ) { Text(app.lernet.engine.text.ExpertWords.EXTERNAL_ADD) }
            OutlinedButton(onClick = { onIntent(ExpertIntent.Refresh) }, enabled = !state.busy) { Text("Обновить") }
        }
        Text(
            "Сбой одного конфига не выключает туннель. Проверки не будят спящие конфиги.",
            color = ExpertColors.muted, fontSize = 13.sp
        )
        val schemeFolders = state.folders.filter { folder ->
            state.draft.folderPolicies.any { it.folderId == folder.id } ||
                state.exits.any { it.folderId == folder.id } ||
                state.profiles.any { it.folderId == folder.id }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Решения 23.10 п.4: все VPN-конфиги, как на телефоне.
            item {
                Text("Все конфиги", color = ExpertColors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Те же, что в режиме VPN. Их можно выбрать целью в схеме или положить в папку.",
                    color = ExpertColors.muted, fontSize = 12.sp,
                )
            }
            if (state.profiles.isEmpty()) {
                item {
                    ExpertEmpty(
                        app.lernet.engine.text.ExpertWords.EXITS_EMPTY,
                        "Добавь конфиг в режиме VPN — ссылкой, QR-кодом или файлом — и он появится здесь.",
                    )
                }
            } else {
                items(state.profiles, key = { "all-" + it.id }) { profile ->
                    val live = state.exits.firstOrNull { it.profileId == profile.id }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            live?.let { selected = it.id } ?: run {
                                importedInterfaceDetails = profile.takeIf { it.interfaceBinding != null }
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, color = ExpertColors.text)
                            val folder = profile.folderId?.let { id -> state.folders.firstOrNull { it.id == id }?.name }
                            Text(
                                listOfNotNull(folder?.let { "папка «$it»" }, profile.protocol.takeIf { it.isNotBlank() }).joinToString(" · ")
                                    .ifBlank { "конфиг VPN" },
                                color = ExpertColors.muted, fontSize = 12.sp,
                            )
                        }
                        live?.let {
                            ExpertTag(exitDisplayName(it, state), exitDisplayColor(it, state))
                        }
                    }
                }
            }
            // Отдельный блок: задействованные в схеме папки со стратегией и состоянием.
            item {
                Text("Задействованные в схеме", color = ExpertColors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Папки и выходы, куда схема уже направила трафик, со стратегией выбора.",
                    color = ExpertColors.muted, fontSize = 12.sp,
                )
            }
            if (schemeFolders.isEmpty() && exits.isEmpty()) {
                item {
                    Text(
                        "Пока схема никуда не направила трафик. Добавь правило или папку — здесь появятся задействованные конфиги.",
                        color = ExpertColors.muted, fontSize = 13.sp,
                    )
                }
            }
            items(schemeFolders, key = { "folder-" + it.id }) { folder ->
                val policy = state.draft.folderPolicies.firstOrNull { it.folderId == folder.id }
                    ?: app.lernet.routing.policy.FolderPolicy(folder.id)
                val members = state.profiles.filter { it.folderId == folder.id }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(folder.name, color = ExpertColors.text, fontWeight = FontWeight.Medium)
                    Text(
                        "Стратегия: ${folderStrategyTitle(policy.selection)}" +
                            (if (policy.autoSwap) " · автосмена" else ""),
                        color = ExpertColors.muted, fontSize = 12.sp,
                    )
                    members.forEach { profile ->
                        val live = state.exits.firstOrNull { it.profileId == profile.id }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(profile.name, color = ExpertColors.text, modifier = Modifier.weight(1f))
                            live?.let { ExpertTag(exitDisplayName(it, state), exitDisplayColor(it, state)) }
                                ?: Text("в схеме", color = ExpertColors.muted, fontSize = 12.sp)
                        }
                    }
                    if (members.isEmpty()) {
                        Text("В папке пока нет конфигов.", color = ExpertColors.muted, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                    OutlinedButton(onClick = { editingFolder = folder.id }, enabled = !state.busy) {
                        Text("Настроить папку")
                    }
                }
            }
            if (exits.isNotEmpty()) {
                item { ExpertExitTableHeader() }
            } else if (sleepingOnly) {
                item {
                    ExpertEmpty(
                        app.lernet.engine.text.ExpertWords.SLEEPING_NONE.substringBefore(". "),
                        "Все конфиги сейчас на связи или ждут проверки. " +
                            app.lernet.engine.text.ExpertWords.SLEEPING_NONE.substringAfter(". "),
                    )
                }
            }
            items(exits, key = { it.id }) { exit ->
                ExpertExitTableRow(
                    exit, state,
                    onOpen = { selected = exit.id },
                    onWake = { onIntent(ExpertIntent.WakeExit(exit.id)) },
                    onSleep = { onIntent(ExpertIntent.SleepExit(exit.id)) },
                )
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
                    }) { Text("Настройки внешнего конфига") }
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
            ReadingRow("Отклик через выход", exit.latencyMs?.let { "$it мс" } ?: "Нет замера")
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
            ExpertMessage("Почему такое состояние", exit.reason ?: "Ядро ещё не передало подробную причину.")
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
                    Text("Настроить холодный старт конфига")
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
internal fun ExpertEvents(
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    modifier: Modifier,
    simulate: (ExpertConnection) -> Unit = {},
    showPath: (ExpertConnection) -> Unit = {},
) {
    // «События»: connections (search, filters, history) moved here from the pult, plus what LerNET did.
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // m6: one segment «Соединения | Действия LerNET», the same on both platforms.
            androidx.compose.material3.FilterChip(tab == 0, { tab = 0 }, label = { Text("Соединения · ${state.connections.count { it.active == true }}") })
            androidx.compose.material3.FilterChip(tab == 1, { tab = 1 }, label = { Text("Действия LerNET · ${state.events.size}") })
        }
        if (tab == 0) {
            ExpertLiveStreams(state, Modifier.weight(1f), simulate, showPath)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Применение схемы, пробуждение, отказ и смена выхода. Каждое событие сохраняет объяснение ядра.",
                Modifier.weight(1f), color = ExpertColors.muted, fontSize = 13.sp,
            )
            OutlinedButton(onClick = { onIntent(ExpertIntent.Refresh) }, enabled = !state.busy) { Text("Обновить") }
        }
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


private val exitColumns = listOf(0.34f, 0.18f, 0.14f, 0.14f, 0.20f)

@Composable
private fun ExpertExitTableHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("Конфиг", "Состояние", "Отклик", "Соединений", "").forEachIndexed { index, title ->
            Text(
                title.uppercase(), Modifier.weight(exitColumns[index]),
                color = ExpertColors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ExpertExitTableRow(
    exit: ExpertExit,
    state: ExpertUiState,
    onOpen: () -> Unit,
    onWake: () -> Unit,
    onSleep: () -> Unit,
) {
    androidx.compose.material3.Surface(
        color = ExpertColors.panel,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(exitColumns[0])) {
                Text(exit.name, color = ExpertColors.text, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(
                    exit.reason ?: exit.targetDescription, color = ExpertColors.muted, fontSize = 12.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.weight(exitColumns[1]), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpertTag(exitDisplayName(exit, state), exitDisplayColor(exit, state))
            }
            Text(
                exit.latencyMs?.let { "$it мс" } ?: "—", Modifier.weight(exitColumns[2]),
                color = if (exit.latencyMs != null) ExpertColors.text else ExpertColors.muted, fontSize = 13.sp,
            )
            Text(
                buildString {
                    append(exit.activeFlows)
                    if (exit.pendingFlows > 0) append(" · ждут ${exit.pendingFlows}")
                },
                Modifier.weight(exitColumns[3]), color = ExpertColors.text, fontSize = 13.sp,
            )
            Row(Modifier.weight(exitColumns[4]), horizontalArrangement = Arrangement.End) {
                when {
                    exit.canWake -> TextButton(onClick = onWake, enabled = !state.busy) { Text("Разбудить") }
                    exit.canSleep -> TextButton(onClick = onSleep, enabled = !state.busy) { Text("Усыпить") }
                    else -> TextButton(onClick = onOpen) { Text("Подробнее") }
                }
            }
        }
    }
}
