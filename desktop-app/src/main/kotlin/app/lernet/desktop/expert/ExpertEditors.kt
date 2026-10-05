package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RouteCompiler
import app.lernet.routing.policy.DestinationRedirect
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyDestinationAddress
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import app.lernet.routing.policy.ProfileExitPolicy
import app.lernet.routing.policy.UnavailableFallback

@Composable
internal fun ExpertModal(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    saveText: String = "Готово",
    content: @Composable () -> Unit,
) {
    val panel: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.width(800.dp).fillMaxHeight(.92f).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when {
                        event.key == Key.Escape -> {
                            onDismiss()
                            true
                        }
                        event.key == Key.Enter && event.isCtrlPressed && canSave -> {
                            onSave()
                            true
                        }
                        else -> false
                    }
                }
            },
            color = ExpertColors.panel, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, ExpertColors.border),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, color = ExpertColors.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = ExpertColors.border)
                ExpertScrollableColumn(Modifier.weight(1f), spacing = 14) { content() }
                HorizontalDivider(color = ExpertColors.border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Отмена · Esc") }
                    Button(onClick = onSave, enabled = canSave) { Text("$saveText · Ctrl+Enter") }
                }
            }
        }
    }
    if (LocalInspectionMode.current) panel() else Dialog(onDismissRequest = onDismiss) { panel() }
}

@Composable
internal fun ExpertRuleEditor(
    original: PolicyNode,
    tree: PolicyTree,
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onSave: (PolicyNode) -> Unit,
) {
    var title by remember(original.id) { mutableStateOf(original.title) }
    var parentId by remember(original.id) { mutableStateOf(original.parentId) }
    var conditions by remember(original.id) { mutableStateOf(original.conditions) }
    var target by remember(original.id) { mutableStateOf(original.target) }
    var protected by remember(original.id) { mutableStateOf(original.protected) }
    var enabled by remember(original.id) { mutableStateOf(original.enabled) }
    var detached by remember(original.id) { mutableStateOf(original.detached) }
    var rewrite by remember(original.id) { mutableStateOf(original.redirect != null) }
    var address by remember(original.id) { mutableStateOf(original.redirect?.address.orEmpty()) }
    var port by remember(original.id) { mutableStateOf(original.redirect?.port?.toString().orEmpty()) }
    var apps by remember { mutableStateOf<Int?>(null) }
    val inheritedProtection = ExpertPolicyEditing.inheritedProtection(tree, parentId)
    val effectiveProtection = protected || inheritedProtection
    val errors = RouteCompiler.validateConditions(original.id, conditions).map { it.message } + buildList {
        if (rewrite && address.isBlank() && port.isBlank()) add("Укажите адрес или порт перенаправления.")
        if (rewrite && address.isNotBlank() && PolicyDestinationAddress.normalize(address.trim()) == null) {
            add("Укажите IP или имя назначения без схемы https:// и пути.")
        }
        if (rewrite && port.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } != true) add("Порт должен быть от 1 до 65535.")
        if (effectiveProtection && (target == PolicyTarget.Direct || targetFallback(target) == UnavailableFallback.DIRECT)) {
            add("Защищённая ветка не может иметь прямой выход или прямой запасной путь.")
        }
        if (tree.nodes.any { it.parentId == original.id } &&
            (target is PolicyTarget.Profile || target is PolicyTarget.Folder || target is PolicyTarget.Channel)
        ) {
            add("Ветка с детьми задаёт условия. Привязку к физическому выходу задайте в конечной ветке.")
        }
    }
    val descendants = ExpertPolicyEditing.descendants(tree, original.id)
    fun save() {
        onSave(
            original.copy(
                title = title.trim(), parentId = parentId, conditions = conditions, target = target,
                protected = protected, enabled = enabled, detached = detached,
                redirect = if (rewrite) DestinationRedirect(address.trim().takeIf(String::isNotBlank), port.toIntOrNull()) else null
            )
        )
    }
    ExpertModal("Правило", onDismiss, ::save, errors.isEmpty() && !pending) {
        EditorPersistenceNotice(state, pending)
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
        ExpertSelect(
            "Родитель",
            listOf(null to "Корень схемы") + tree.nodes.filter { candidate ->
                candidate.id !in descendants &&
                    candidate.target !is PolicyTarget.Profile &&
                    candidate.target !is PolicyTarget.Folder &&
                    candidate.target !is PolicyTarget.Channel
            }
                .map { it.id to it.title.ifBlank { "Без названия" } },
            parentId
        ) { parentId = it }
        ExpertPolicyEditing.inactiveReason(original, tree, state)?.let {
            ExpertMessage("Ветка не выполняется на Windows", it, warning = true)
            if (original.protected) {
                ExpertMessage(
                    "Защита этой ветки здесь не действует",
                    "Неактивная ветка не проверяет трафик. Если причина — условие Android, добавьте отдельную защищённую ветку " +
                        "с программой Windows. Исходное правило сохранится для Android.",
                    warning = true
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Между условиями", color = ExpertColors.muted)
            FilterChip(conditions.join == MatchJoin.AND, { conditions = conditions.copy(join = MatchJoin.AND) }, label = { Text("И") })
            FilterChip(conditions.join == MatchJoin.OR, { conditions = conditions.copy(join = MatchJoin.OR) }, label = { Text("ИЛИ") })
        }
        Text(
            "Значения внутри одного поля объединяются через ИЛИ. Пустое правило охватывает весь трафик своей ветки.",
            color = ExpertColors.muted, fontSize = 12.sp
        )
        conditions.blocks.forEachIndexed { index, block ->
            var rawValues by remember(original.id, index, block.kind) { mutableStateOf(block.values.joinToString("\n")) }
            LaunchedEffect(block.values) {
                if (rawValues.lines().map { it.trim() }.filter { it.isNotBlank() } != block.values) {
                    rawValues = block.values.joinToString("\n")
                }
            }
            Surface(color = ExpertColors.background, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ExpertPolicyEditing.kindName(block.kind), Modifier.weight(1f), color = ExpertColors.text,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = {
                            conditions = conditions.copy(
                                blocks = conditions.blocks.filterIndexed { current, _ ->
                                    current != index
                                }
                            )
                        }) {
                            Text("Удалить условие")
                        }
                    }
                    if (block.kind == ConditionKind.APP) {
                        ExpertMessage(
                            "Условие Android сохранено",
                            "Пакеты Android не определяют программы Windows. Ветка останется в архиве; добавьте отдельное условие Windows.",
                            warning = true,
                        )
                    }
                    if (block.kind == ConditionKind.PRIVATE) {
                        Text(
                            "Локальные адреса устройства и частных сетей. " +
                                "Имена корпоративных сайтов могут потребовать отдельное DNS-правило.",
                            color = ExpertColors.muted, fontSize = 13.sp
                        )
                    } else {
                        OutlinedTextField(
                            rawValues, { raw ->
                                rawValues = raw
                                val changed = block.copy(values = raw.lines().map { it.trim() }.filter { it.isNotBlank() })
                                conditions = conditions.copy(
                                    blocks = conditions.blocks.mapIndexed {
                                            current,
                                            value
                                        ->
                                        if (current == index) changed else value
                                    }
                                )
                            }, Modifier.fillMaxWidth(), label = { Text("Значения, по одному в строке") },
                            supportingText = { Text(conditionHint(block.kind)) }, minLines = 2, maxLines = 5
                        )
                    }
                    if (block.kind == ConditionKind.PROCESS) OutlinedButton(onClick = { apps = index }) { Text("Выбрать программы") }
                }
            }
        }
        ExpertSelect<ConditionKind?>(
            "Добавить условие",
            listOf(null to "Выберите тип") + ConditionKind.entries.map {
                it to ExpertPolicyEditing.kindName(it)
            },
            null
        ) {
            if (it != null) {
                conditions = conditions.copy(
                    blocks = conditions.blocks + ConditionBlock(
                        it,
                        if (it == ConditionKind.PRIVATE) listOf("private") else emptyList()
                    )
                )
            }
        }
        HorizontalDivider(color = ExpertColors.border)
        if (inheritedProtection) {
            ExpertMessage(
                "Защита унаследована от родителя",
                "Этот трафик уже обязан использовать защищённый путь. Снятие собственной отметки не отменяет защиту родительской ветки."
            )
        }
        ExpertTargetEditor(target, tree.scope, state, effectiveProtection) { target = it }
        if (conditions.blocks.any { it.kind == ConditionKind.DOMAIN }) DomainRecognitionQualification()
        ExpertToggle(
            "Только защищённый путь",
            "Если выход недоступен, прямой путь запрещён трафику, совпавшему с этой веткой.", protected
        ) {
            protected = it
            if (it && targetFallback(target) == UnavailableFallback.DIRECT) target = withFallback(target, UnavailableFallback.BLOCK)
        }
        ExpertToggle(
            "Перенаправить назначение",
            "Меняет адрес или порт, к которому подключается приложение. Это отдельная операция от выбора VPN.", rewrite
        ) { rewrite = it }
        if (rewrite) {
            OutlinedTextField(
                address, { address = it }, Modifier.fillMaxWidth(), label = {
                    Text("Новый адрес или имя")
                }, singleLine = true,
                supportingText = { Text("IP или имя без https:// и пути. Пустое поле сохраняет исходный адрес.") }
            )
            OutlinedTextField(
                port, { port = it }, Modifier.fillMaxWidth(), label = { Text("Новый порт") }, singleLine = true,
                supportingText = { Text("Пустое поле сохраняет исходный порт.") }
            )
        }
        ExpertToggle("Ветка включена", "Выключенная ветка сохраняется в схеме и не участвует в выборе пути.", enabled) { enabled = it }
        ExpertToggle(
            "Хранить отдельно от дерева",
            "Отсоединённое правило можно редактировать, но оно не выполняется.", detached
        ) { detached = it }
        if (errors.isNotEmpty()) ExpertMessage("Проверьте правило", errors.joinToString("\n"), error = true)
    }
    apps?.let { index ->
        val block = conditions.blocks.getOrNull(index)
        if (block != null) {
            ExpertApplicationPicker(state, block.values.toSet(), onIntent, { apps = null }) { selected ->
                conditions = conditions.copy(
                    blocks = conditions.blocks.mapIndexed {
                            current,
                            value
                        ->
                        if (current == index) value.copy(values = selected.toList()) else value
                    }
                )
                apps = null
            }
        }
    }
}

@Composable
internal fun ExpertTargetEditor(
    target: PolicyTarget,
    scope: PolicyScope,
    state: ExpertUiState,
    protected: Boolean,
    allowChannels: Boolean = true,
    excludedChannelId: String? = null,
    onChange: (PolicyTarget) -> Unit,
) {
    val options = buildList<Pair<String, String>> {
        add("direct" to "Напрямую")
        add("block" to "Запретить")
        if (scope != PolicyScope.Device) add("current" to "Выход этой схемы")
        state.folders.forEach { add("folder:${it.id}" to "Папка: ${it.name}") }
        state.profiles.forEach { add("profile:${it.id}" to "Профиль: ${it.name}") }
        if (allowChannels) {
            state.draft.channels.filter { it.owner == scope && it.id != excludedChannelId }
                .forEach { add("channel:${it.id}" to "Канал: ${it.name}") }
        }
    }
    val key = when (target) {
        PolicyTarget.Direct -> "direct"
        PolicyTarget.Block -> "block"
        PolicyTarget.CurrentExit -> "current"
        is PolicyTarget.Profile -> "profile:${target.id}"
        is PolicyTarget.Folder -> "folder:${target.id}"
        is PolicyTarget.Channel -> "channel:${target.id}"
    }
    ExpertSelect("Куда направить", options, key) { value ->
        onChange(
            when {
                value == "direct" -> PolicyTarget.Direct
                value == "block" -> PolicyTarget.Block
                value == "current" -> PolicyTarget.CurrentExit
                value.startsWith("profile:") -> PolicyTarget.Profile(value.removePrefix("profile:"))
                value.startsWith("folder:") -> PolicyTarget.Folder(value.removePrefix("folder:"), routeScope = null)
                value.startsWith("channel:") -> PolicyTarget.Channel(value.removePrefix("channel:"))
                else -> error("Unknown target option")
            }
        )
    }
    when (target) {
        is PolicyTarget.Profile -> {
            val profile = state.profiles.firstOrNull { it.id == target.id }
            val scopeOptions = buildList<Pair<PolicyScope?, String>> {
                add(null to "Только прокси, без дерева профиля")
                add(PolicyScope.Profile(target.id) to "Собственное дерево профиля")
                profile?.folderId?.let {
                    add(PolicyScope.Folder(it) to "Дерево папки: ${ExpertPolicyEditing.scopeName(PolicyScope.Folder(it), state)}")
                }
            }
            ExpertSelect("Дочерняя схема", scopeOptions, target.routeScope) { onChange(target.copy(routeScope = it)) }
            FallbackSelector(target.fallback, protected) { onChange(target.copy(fallback = it)) }
        }
        is PolicyTarget.Folder -> {
            ExpertToggle(
                "Использовать дерево папки",
                "Совпавший трафик продолжит путь по собственной схеме этой папки.", target.routeScope != null
            ) {
                onChange(target.copy(routeScope = if (it) PolicyScope.Folder(target.id) else null))
            }
            FallbackSelector(target.fallback, protected) { onChange(target.copy(fallback = it)) }
        }
        is PolicyTarget.Channel -> {
            val linked = state.draft.channels.firstOrNull { it.id == target.id }
            Text(
                "Все ветки, которые ссылаются на этот канал, делят один выход и его настройки сна. " +
                    "Текущая цель: ${linked?.let { ExpertPolicyEditing.targetName(it.target, state) } ?: "канал удалён"}.",
                color = ExpertColors.muted, fontSize = 13.sp
            )
        }
        PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit -> Unit
    }
}

@Composable
private fun FallbackSelector(current: UnavailableFallback, protected: Boolean, onChange: (UnavailableFallback) -> Unit) {
    ExpertSelect(
        "Если выход недоступен",
        if (protected) {
            listOf(UnavailableFallback.BLOCK to "Запретить")
        } else {
            listOf(
                UnavailableFallback.BLOCK to "Запретить", UnavailableFallback.DIRECT to "Разрешить напрямую"
            )
        },
        current, onChange
    )
    Text(
        "Прямой запасной путь раскрывает обычный IP устройства. Выберите его только для трафика, которому это допустимо.",
        color = ExpertColors.muted, fontSize = 12.sp
    )
}

@Composable
internal fun ExpertDefaultTargetEditor(
    tree: PolicyTree,
    state: ExpertUiState,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onSave: (PolicyTarget) -> Unit,
) {
    var target by remember { mutableStateOf(tree.defaultTarget) }
    ExpertModal("Путь по умолчанию", onDismiss, { onSave(target) }, !pending) {
        EditorPersistenceNotice(state, pending)
        ExpertMessage("Последнее действие схемы", "Этот путь выбирается, если ни одно правило схемы не подошло.")
        ExpertTargetEditor(target, tree.scope, state, false) { target = it }
    }
}

@Composable
internal fun ExpertChannelEditor(
    original: PolicyChannel,
    state: ExpertUiState,
    onDismiss: () -> Unit,
    onSave: (PolicyChannel) -> Unit,
    onDelete: () -> Unit,
    pending: Boolean = false,
) {
    var name by remember { mutableStateOf(original.name) }
    var target by remember { mutableStateOf(original.target) }
    var lifecycle by remember { mutableStateOf(original.lifecycle) }
    var lifecycleValid by remember { mutableStateOf(true) }
    val references = (listOf(state.draft.device) + state.draft.trees).flatMap { it.nodes }
        .filter { it.target == PolicyTarget.Channel(original.id) }
    val rootReference = (listOf(state.draft.device) + state.draft.trees).any { it.defaultTarget == PolicyTarget.Channel(original.id) }
    val channelReferences = state.draft.channels.filter { it.target == PolicyTarget.Channel(original.id) }
    ExpertModal(
        "Общий канал", onDismiss, { onSave(original.copy(name = name.trim(), target = target, lifecycle = lifecycle)) },
        name.isNotBlank() && lifecycleValid && !pending
    ) {
        EditorPersistenceNotice(state, pending)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название канала") }, singleLine = true)
        ExpertMessage(
            "Один физический выход",
            "Канал связан с ветками по идентификатору. Переименование не создаёт новое соединение и не разрывает связи."
        )
        if (references.isEmpty() && !rootReference && channelReferences.isEmpty()) {
            Text(
                "Входящих веток пока нет.",
                color = ExpertColors.muted
            )
        } else {
            Text("Связанные ветки", color = ExpertColors.text, fontWeight = FontWeight.Medium)
            references.forEach { Text(it.title.ifBlank { "Без названия" }, color = ExpertColors.muted) }
            channelReferences.forEach { Text("Канал: ${it.name}", color = ExpertColors.muted) }
            if (rootReference) Text("Также используется путём по умолчанию.", color = ExpertColors.muted)
        }
        ExpertTargetEditor(target, original.owner, state, references.any { it.protected }, excludedChannelId = original.id) { target = it }
        ExpertLifecycleEditor(lifecycle, { lifecycleValid = it }) { lifecycle = it }
        if (references.isEmpty() && !rootReference && channelReferences.isEmpty() && state.draft.channels.any { it.id == original.id }) {
            TextButton(onClick = onDelete) { Text("Удалить неиспользуемый канал", color = ExpertColors.red) }
        } else if (references.isNotEmpty() || rootReference || channelReferences.isNotEmpty()) {
            Text(
                "Для удаления сначала переведите все входящие ветки и пути по умолчанию на другую цель.",
                color = ExpertColors.muted, fontSize = 12.sp
            )
        }
    }
}

@Composable
internal fun ExpertLifecycleEditor(
    value: ExitLifecyclePolicy,
    onValidity: (Boolean) -> Unit = {},
    onChange: (ExitLifecyclePolicy) -> Unit,
) {
    var idle by remember { mutableStateOf(ExpertLifecycleValues.seconds(value.idleTimeoutMs)) }
    var firstFlow by remember { mutableStateOf(ExpertLifecycleValues.seconds(value.firstFlowTimeoutMs)) }
    var startup by remember { mutableStateOf(ExpertLifecycleValues.seconds(value.startupTimeoutMs)) }
    var pendingLimit by remember { mutableStateOf(value.maxPendingFlows.toString()) }
    var advanced by remember { mutableStateOf(false) }
    val idleMs = ExpertLifecycleValues.milliseconds(idle, 86_400_000)
    val firstFlowMs = ExpertLifecycleValues.milliseconds(firstFlow, 45_000)
    val startupMs = ExpertLifecycleValues.milliseconds(startup, 45_000)
    val pendingCount = pendingLimit.toIntOrNull()?.takeIf { it in 1..1_000 }
    val valid = idleMs != null && firstFlowMs != null && startupMs != null && pendingCount != null
    LaunchedEffect(valid) { onValidity(valid) }
    ExpertToggle(
        "Холодный старт",
        "Выход спит до первого пользовательского соединения. Проверки связи сами не будят выход.", value.coldStart
    ) {
        onChange(value.copy(coldStart = it))
    }
    OutlinedTextField(
        idle, { raw ->
            idle = raw
            ExpertLifecycleValues.milliseconds(raw, 86_400_000)?.let { onChange(value.copy(idleTimeoutMs = it)) }
        }, Modifier.fillMaxWidth(), label = { Text("Засыпать после тишины, секунд") }, singleLine = true, isError = idleMs == null,
        supportingText = { Text("1–86400 секунд. Например, 900 секунд — 15 минут. Живые соединения не закрываются ради сна.") }
    )
    OutlinedTextField(
        firstFlow, { raw ->
            firstFlow = raw
            ExpertLifecycleValues.milliseconds(raw, 45_000)?.let { onChange(value.copy(firstFlowTimeoutMs = it)) }
        }, Modifier.fillMaxWidth(), label = { Text("Сколько ждать первое соединение, секунд") }, singleLine = true,
        isError = firstFlowMs == null, supportingText = { Text("1–45 секунд. Когда ожидание закончится, сработает правило отказа.") }
    )
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Скрыть ограничения очереди" else "Ограничения запуска и очереди") }
    if (advanced) {
        OutlinedTextField(
            startup, { raw ->
                startup = raw
                ExpertLifecycleValues.milliseconds(raw, 45_000)?.let { onChange(value.copy(startupTimeoutMs = it)) }
            }, Modifier.fillMaxWidth(), label = { Text("Ожидание запуска выхода, секунд") }, singleLine = true, isError = startupMs == null,
            supportingText = { Text("1–45 секунд. Запуск и ожидание первого запроса имеют отдельные ограничения.") }
        )
        OutlinedTextField(
            pendingLimit, { raw ->
                pendingLimit = raw
                raw.toIntOrNull()?.takeIf { it in 1..1_000 }?.let { onChange(value.copy(maxPendingFlows = it)) }
            }, Modifier.fillMaxWidth(), label = { Text("Максимум запросов в очереди") }, singleLine = true, isError = pendingCount == null,
            supportingText = { Text("1–1000 запросов. Переполнение обрабатывается правилом отказа.") }
        )
    }
    if (!valid) {
        ExpertMessage(
            "Проверьте ограничения",
            "Время простоя: 1–86400 секунд; ожидание: 1–45 секунд; " +
                "очередь: 1–1000 запросов. Можно указать до трёх знаков после запятой. Откройте ограничения очереди для " +
                "скрытых полей.",
            error = true
        )
    }
    Text(
        "Таймер продлевается пользовательским трафиком. Живое соединение не закрывается ради сна. " +
            "Пока выход просыпается, запрос ожидает не более ${value.firstFlowTimeoutMs / 1000} секунд; " +
            "в очереди может быть до ${value.maxPendingFlows} запросов.",
        color = ExpertColors.muted, fontSize = 13.sp
    )
}

@Composable
internal fun ExpertFolderEditor(
    folderId: String,
    state: ExpertUiState,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onSave: (FolderPolicy) -> Unit,
) {
    var policy by remember { mutableStateOf(state.draft.folderPolicies.firstOrNull { it.folderId == folderId } ?: FolderPolicy(folderId)) }
    var lifecycleValid by remember { mutableStateOf(true) }
    val members = state.profiles.filter { it.folderId == folderId }
    ExpertModal("Политика папки", onDismiss, { onSave(policy) }, !pending && lifecycleValid) {
        EditorPersistenceNotice(state, pending)
        ExpertSelect(
            "Как выбирать профиль",
            listOf(
                FolderSelection.PREFERRED to "Предпочтительный",
                FolderSelection.LOWEST_LATENCY to "Наименьшая задержка"
            ),
            policy.selection
        ) { policy = policy.copy(selection = it) }
        ExpertSelect(
            "Предпочтительный профиль",
            listOf(null to "Первый доступный") + members.map {
                it.id to it.name
            },
            policy.preferredProfileId
        ) {
            policy = policy.copy(preferredProfileId = it)
        }
        ExpertToggle(
            "Автоматическая смена",
            "Когда активный выход перестаёт передавать данные, выбирается доступный сосед. " +
                "Без этой опции повторяется текущий профиль.",
            policy.autoSwap
        ) { policy = policy.copy(autoSwap = it) }
        ExpertMessage(
            "Измеряем весь путь",
            "Ответ TCP/TLS сервера ещё не доказывает, что туннель работает. Для выбора используется свежая проверка через выход."
        )
        if (members.isEmpty()) {
            ExpertMessage(
                "Папка пуста",
                "Пока сюда не добавлен профиль, трафик будет использовать заданное правило отказа.", warning = true
            )
        }
        Text(
            "Здоровый текущий выход сохраняется. Добавление профиля не должно вызывать лишнее переключение действующих соединений.",
            color = ExpertColors.muted, fontSize = 13.sp
        )
        HorizontalDivider(color = ExpertColors.border)
        Text("Холодный старт и сон папки", color = ExpertColors.text, fontWeight = FontWeight.SemiBold)
        ExpertLifecycleEditor(policy.lifecycle, { lifecycleValid = it }) { policy = policy.copy(lifecycle = it) }
        Text(
            "Эти настройки действуют для выхода папки. Если ветка использует отдельный канал, его настройки имеют приоритет.",
            color = ExpertColors.muted, fontSize = 12.sp
        )
    }
}

@Composable
internal fun ExpertProfileLifecycleEditor(
    profileId: String,
    state: ExpertUiState,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onSave: (ProfileExitPolicy) -> Unit,
) {
    var policy by remember {
        mutableStateOf(
            state.draft.profilePolicies.firstOrNull { it.profileId == profileId }
                ?: ProfileExitPolicy(profileId)
        )
    }
    var valid by remember { mutableStateOf(true) }
    ExpertModal("Холодный старт профиля", onDismiss, { onSave(policy) }, valid && !pending) {
        EditorPersistenceNotice(state, pending)
        Text(
            state.profiles.firstOrNull { it.id == profileId }?.name ?: "Профиль недоступен", color = ExpertColors.text,
            fontWeight = FontWeight.SemiBold
        )
        ExpertLifecycleEditor(policy.lifecycle, { valid = it }) { policy = policy.copy(lifecycle = it) }
        ExpertMessage(
            "Самостоятельный выход профиля",
            "При выборе через папку действуют настройки папки; " +
                "при выборе отдельного канала — настройки канала. Сам профиль не нужно помещать в канал ради холодного старта."
        )
    }
}

@Composable
private fun EditorPersistenceNotice(state: ExpertUiState, pending: Boolean) {
    if (pending) ExpertMessage("Сохраняем черновик", "Окно закроется после подтверждения записи. Активная сеть не изменяется.")
    state.error?.let { ExpertMessage("Изменение не подтверждено", it, error = true) }
}

@Composable
internal fun <T> ExpertSelect(label: String, options: List<Pair<T, String>>, selected: T, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = ExpertColors.muted, fontSize = 12.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Сохранённое значение недоступно")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.second) }, onClick = {
                        expanded = false
                        onChange(option.first)
                    })
                }
            }
        }
    }
}

@Composable
internal fun ExpertToggle(title: String, explanation: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ExpertColors.text, fontWeight = FontWeight.Medium)
            Text(explanation, color = ExpertColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Switch(value, onCheckedChange = null)
    }
}

@Composable
private fun ExpertApplicationPicker(
    state: ExpertUiState,
    existing: Set<String>,
    onIntent: (ExpertIntent) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(existing) }
    var query by remember { mutableStateOf("") }
    var runningOnly by remember { mutableStateOf(false) }
    var installedOnly by remember { mutableStateOf(false) }
    val applications = state.applications.filter { application ->
        (!runningOnly || application.running) &&
            (!installedOnly || application.installed) &&
            (query.isBlank() || application.name.contains(query, true) || application.executable.contains(query, true))
    }.distinctBy { it.executable.lowercase() }.sortedBy { it.name.lowercase() }
    ExpertModal("Программы Windows", onDismiss, { onSave(selected) }, true, "Выбрать") {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Имя программы или путь") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(runningOnly, { runningOnly = !runningOnly }, label = { Text("Запущенные") })
            FilterChip(installedOnly, { installedOnly = !installedOnly }, label = { Text("Установленные") })
            OutlinedButton(onClick = {
                runningOnly = false
                installedOnly = false
                query = ""
                onIntent(ExpertIntent.ChooseExecutable)
            }) { Text("Выбрать .exe") }
        }
        Text(
            "Выбрано: ${selected.size}. Сохраняются имена процессов или полные пути, а не положение программы в списке.",
            color = ExpertColors.muted, fontSize = 12.sp
        )
        if (applications.isEmpty()) ExpertEmpty("Программы не найдены", "Измените фильтр или укажите исполняемый файл вручную.")
        if (applications.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().height(340.dp)) {
                items(applications, key = { it.executable.lowercase() }) { app ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(app.executable in selected, role = Role.Checkbox, onValueChange = { checked ->
                            selected = if (checked) selected + app.executable else selected - app.executable
                        }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(app.executable in selected, onCheckedChange = null)
                        if (app.icon != null) {
                            Image(app.icon, contentDescription = null, modifier = Modifier.size(30.dp))
                        } else {
                            Surface(color = ExpertColors.blue.copy(alpha = .1f), shape = RoundedCornerShape(8.dp)) {
                                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        app.name.take(1),
                                        color = ExpertColors.blue
                                    )
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(app.name, color = ExpertColors.text)
                            Text(app.executable, color = ExpertColors.muted, fontSize = 11.sp)
                        }
                        if (app.running) ExpertTag("Запущена", ExpertColors.green)
                    }
                }
            }
        }
        val absent = selected.filter { path -> state.applications.none { it.executable.equals(path, true) } }
        if (absent.isNotEmpty()) {
            ExpertMessage(
                "Сохранённые пути вне каталога",
                "Они сохраняются при выборе других программ. Можно снять отметку, чтобы удалить ненужный путь."
            )
            absent.forEach { path ->
                Row(
                    Modifier.fillMaxWidth().toggleable(true, role = Role.Checkbox, onValueChange = { selected = selected - path }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Checkbox(true, onCheckedChange = null)
                    Text(path, Modifier.weight(1f), color = ExpertColors.muted, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun conditionHint(kind: ConditionKind): String = when (kind) {
    ConditionKind.DOMAIN -> "Например, example.org или *.example.org. ! перед значением означает исключение."
    ConditionKind.GEOIP -> "Двухбуквенный код страны: RU, DE, NL. Страна IP не определяет язык или владельца сайта."
    ConditionKind.PRIVATE -> "Частные адреса локальной сети."
    ConditionKind.CIDR -> "Например, 192.168.1.0/24, 10.0.0.5/32 или IPv6-подсеть."
    ConditionKind.APP -> "Пакеты Android, например org.telegram.messenger. Windows не использует этот идентификатор."
    ConditionKind.PROCESS -> "Имя процесса, например chrome.exe, или полный путь к исполняемому файлу."
}

private fun targetFallback(target: PolicyTarget): UnavailableFallback? = when (target) {
    is PolicyTarget.Profile -> target.fallback
    is PolicyTarget.Folder -> target.fallback
    PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel -> null
}

private fun withFallback(target: PolicyTarget, fallback: UnavailableFallback): PolicyTarget = when (target) {
    is PolicyTarget.Profile -> target.copy(fallback = fallback)
    is PolicyTarget.Folder -> target.copy(fallback = fallback)
    PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel -> target
}
