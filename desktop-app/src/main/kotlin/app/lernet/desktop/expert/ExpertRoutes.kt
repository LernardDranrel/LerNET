package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.desktop.RouteCanvasTransform
import app.lernet.desktop.RouteEditorCanvas
import app.lernet.desktop.RouteEditorInspector
import app.lernet.desktop.RouteEditorNodeActions
import app.lernet.desktop.RouteEditorNodeFace
import app.lernet.desktop.RouteEditorPositioned
import app.lernet.desktop.RouteEditorView
import app.lernet.desktop.RouteEditorViewToggle
import app.lernet.desktop.routeEditorDrag
import app.lernet.desktop.routeEditorStateSaver
import app.lernet.routing.RouteLayoutNode
import app.lernet.routing.RouteTreeLayout
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import java.util.UUID

@Composable
internal fun ExpertRoutes(
    state: ExpertUiState,
    onIntent: (ExpertIntent) -> Unit,
    modifier: Modifier,
    highlightedNodeIds: Set<String> = emptySet(),
) {
    val tree = PolicyBranchEditing.displayTree(ExpertPolicyEditing.tree(state.draft, state.selectedScope))
    val rootOtherwise = tree.nodes.first { it.parentId == null && !it.detached && PolicyOtherwise.isOtherwise(it) }
    var view by rememberSaveable { mutableStateOf(RouteEditorView.SCHEME) }
    var ownerMenu by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable(state.selectedScope) { mutableStateOf<String?>(null) }
    var rootSelected by rememberSaveable(state.selectedScope) { mutableStateOf(true) }
    var editing by rememberSaveable(state.selectedScope, stateSaver = routeEditorStateSaver<PolicyNode?>()) {
        mutableStateOf<PolicyNode?>(null)
    }
    var deleting by remember(state.selectedScope) { mutableStateOf<PolicyNode?>(null) }
    var editingChannel by rememberSaveable(state.selectedScope, stateSaver = routeEditorStateSaver<PolicyChannel?>()) {
        mutableStateOf<PolicyChannel?>(null)
    }
    var folderSettings by rememberSaveable(state.selectedScope) { mutableStateOf(false) }
    var profileSettings by rememberSaveable(state.selectedScope) { mutableStateOf(false) }
    var pendingPolicy by remember { mutableStateOf<ExpertDraftSubmission?>(null) }
    var pendingCompletion by remember { mutableStateOf<(() -> Unit)?>(null) }
    val focus = remember { FocusRequester() }
    val selected = tree.nodes.firstOrNull { it.id == selectedId }
    val graphProblem = remember(tree) { ExpertPolicyEditing.graphProblem(tree) }
    fun editPolicy(updated: app.lernet.routing.policy.NetworkPolicy) = onIntent(ExpertIntent.EditPolicy(updated))
    fun commitEditor(updated: app.lernet.routing.policy.NetworkPolicy, onComplete: () -> Unit) {
        if (updated == state.draft && state.error == null) {
            onComplete()
            return
        }
        pendingPolicy = ExpertDraftSubmission.capture(updated, state)
        pendingCompletion = onComplete
        editPolicy(updated)
    }
    LaunchedEffect(state.draft, state.error, state.events.lastOrNull()?.id, pendingPolicy) {
        when (pendingPolicy?.response(state)) {
            ExpertDraftResponse.CONFIRMED -> {
                pendingCompletion?.invoke()
                pendingPolicy = null
                pendingCompletion = null
            }
            ExpertDraftResponse.FAILED -> {
                pendingPolicy = null
                pendingCompletion = null
            }
            ExpertDraftResponse.WAITING, null -> Unit
        }
    }
    fun newNode(parent: PolicyNode? = null) {
        editing = PolicyNode(
            UUID.randomUUID().toString(), parentId = parent?.id,
            sortIndex = tree.nodes.count { it.parentId == parent?.id },
            target = PolicyBranchEditing.initialChildTarget(tree, parent?.id),
        )
    }
    fun newOtherwise(parent: PolicyNode) {
        editing = PolicyNode(
            UUID.randomUUID().toString(), parentId = parent.id,
            sortIndex = tree.nodes.count { it.parentId == parent.id }, title = "ИНАЧЕ",
            target = if (ExpertPolicyEditing.inheritedProtection(tree, parent.id)) PolicyTarget.Block else tree.defaultTarget,
            otherwise = true,
        )
    }
    Column(
        modifier.focusRequester(focus).focusable().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                event.key in setOf(Key.Delete, Key.Backspace) &&
                selected != null &&
                !PolicyOtherwise.isOtherwise(selected) &&
                editing == null &&
                deleting == null &&
                editingChannel == null &&
                !folderSettings &&
                !profileSettings
            ) {
                deleting = selected
                true
            } else {
                false
            }
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { ownerMenu = true }) { Text(ExpertPolicyEditing.scopeName(state.selectedScope, state) + " ▾") }
                    DropdownMenu(expanded = ownerMenu, onDismissRequest = { ownerMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Всё устройство", fontWeight = FontWeight.SemiBold) },
                            onClick = {
                                ownerMenu = false
                                onIntent(ExpertIntent.SelectScope(PolicyScope.Device))
                            },
                        )
                        state.folders.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text("Папка: ${folder.name}", fontWeight = FontWeight.SemiBold) },
                                onClick = {
                                    ownerMenu = false
                                    onIntent(ExpertIntent.SelectScope(PolicyScope.Folder(folder.id)))
                                },
                            )
                            state.profiles.filter { it.folderId == folder.id }.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name, Modifier.padding(start = 18.dp)) },
                                    onClick = {
                                        ownerMenu = false
                                        onIntent(ExpertIntent.SelectScope(PolicyScope.Profile(profile.id)))
                                    },
                                )
                            }
                        }
                        state.profiles.filter {
                            it.folderId == null ||
                                state.folders.none { folder ->
                                    folder.id == it.folderId
                                }
                        }.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name) },
                                onClick = {
                                    ownerMenu = false
                                    onIntent(ExpertIntent.SelectScope(PolicyScope.Profile(profile.id)))
                                },
                            )
                        }
                    }
                }
                RouteEditorViewToggle(view) { view = it }
                OutlinedButton(
                    onClick = {
                        editingChannel = PolicyChannel(
                            UUID.randomUUID().toString(), "", tree.scope,
                            target = if (tree.scope == PolicyScope.Device) PolicyTarget.Direct else PolicyTarget.CurrentExit
                        )
                    },
                    enabled = !state.busy,
                ) { Text("Новый канал") }
                if (state.selectedScope is PolicyScope.Folder) TextButton(onClick = { folderSettings = true }) { Text("Политика папки") }
                if (state.selectedScope is PolicyScope.Profile) TextButton(onClick = { profileSettings = true }) { Text("Холодный старт") }
            }
            Button(onClick = { newNode() }, enabled = !state.busy) { Text("Добавить правило") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ИНАЧЕ — последний путь на каждой развилке. Его можно продолжить дочерними условиями.", Modifier.weight(1f),
                color = ExpertColors.muted, fontSize = 13.sp
            )
            TextButton(onClick = {
                selectedId = rootOtherwise.id
                rootSelected = false
            }) { Text("Выбрать ИНАЧЕ") }
        }
        Text(
            "Сначала проверяются ветки по порядку, затем ИНАЧЕ. Условия родителя и ребёнка объединяются через И. " +
                "Каналы с одним идентификатором — один выход; название служит только подписью.",
            color = ExpertColors.muted, fontSize = 12.sp,
        )
        val nodeSelect: (PolicyNode) -> Unit = {
            selectedId = it.id
            rootSelected = false
            if (view == RouteEditorView.LIST || graphProblem != null) focus.requestFocus()
        }
        graphProblem?.let { ExpertMessage("Схема показана списком", it, warning = true) }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (if (graphProblem != null) RouteEditorView.LIST else view) {
                    RouteEditorView.SCHEME -> ExpertGraph(
                        tree, state, selectedId, Modifier.fillMaxSize(), nodeSelect,
                        { editingChannel = it }, {
                            selectedId = null
                            rootSelected = true
                        },
                        addChild = { newNode(it) },
                        addRoot = { newNode() },
                        highlightedNodeIds = highlightedNodeIds,
                        selectedRoot = rootSelected,
                        updateLayout = { points, clear -> onIntent(ExpertIntent.UpdateLayout(tree.scope, points, clear)) },
                    )
                    RouteEditorView.LIST -> ExpertRouteList(
                        tree, state, selectedId, Modifier.fillMaxSize(), nodeSelect,
                        { editing = it }, { newNode(it) }, { deleting = it }, { editingChannel = it },
                        rootSelect = {
                            selectedId = null
                            rootSelected = true
                        }, addRoot = { newNode() }
                    )
                }
            }
            RouteEditorInspector("ПРАВИЛО", Modifier.width(302.dp).fillMaxHeight()) {
                if (rootSelected) {
                    Text("Весь трафик", color = ExpertColors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Проверка условий → ИНАЧЕ", color = ExpertColors.blue)
                    OutlinedButton(onClick = { editing = rootOtherwise }) { Text("Настроить ИНАЧЕ") }
                    OutlinedButton(onClick = { newNode() }) { Text("+ Дочернее правило") }
                } else if (selected == null) {
                    Text("Выберите блок на схеме или строку в списке", color = ExpertColors.text, fontSize = 16.sp)
                    Text(
                        "Перетаскивайте блоки на схеме. Свойства, порядок и целевой выход доступны здесь.",
                        color = ExpertColors.muted, fontSize = 12.sp,
                    )
                }
                selected?.let { node ->
                    Text(policyNodeTitle(node), color = ExpertColors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(ExpertPolicyEditing.conditionSummary(node), color = ExpertColors.muted, fontSize = 12.sp)
                    Text(policyNodeTargetLabel(node, tree, state), color = ExpertColors.blue, fontSize = 13.sp)
                    ExpertPolicyEditing.inactiveReason(node, tree, state)?.let { Text(it, color = ExpertColors.muted, fontSize = 12.sp) }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { editing = node }) { Text("Свойства") }
                        if (canAddPolicyChild(node)) OutlinedButton(onClick = { newNode(node) }) { Text("+ Дочернее правило") }
                        if (canAddMissingOtherwise(tree, node)) {
                            OutlinedButton(onClick = { newOtherwise(node) }) { Text("+ Ветка ИНАЧЕ") }
                        }
                        if (!PolicyOtherwise.isOtherwise(node)) {
                            OutlinedButton(enabled = canMovePolicyNode(tree, node, -1), onClick = {
                                editPolicy(
                                    ExpertPolicyEditing.moveNode(
                                        state.draft, tree.scope, node.id,
                                        -1
                                    )
                                )
                            }) { Text("Выше") }
                            OutlinedButton(enabled = canMovePolicyNode(tree, node, 1), onClick = {
                                editPolicy(ExpertPolicyEditing.moveNode(state.draft, tree.scope, node.id, 1))
                            }) { Text("Ниже") }
                            OutlinedButton(onClick = {
                                editPolicy(
                                    ExpertPolicyEditing.putNode(
                                        state.draft, tree.scope,
                                        node.copy(enabled = !node.enabled)
                                    )
                                )
                            }) {
                                Text(if (node.enabled) "Выключить ветку" else "Включить ветку")
                            }
                            TextButton(onClick = { deleting = node }) { Text("Удалить · Delete", color = ExpertColors.red) }
                        }
                        val referencedScope = when (val target = node.target) {
                            is PolicyTarget.Profile -> target.routeScope
                            is PolicyTarget.Folder -> target.routeScope
                            PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel -> null
                        }
                        referencedScope?.let { scope ->
                            OutlinedButton(onClick = { onIntent(ExpertIntent.SelectScope(scope)) }) { Text("Открыть дочернюю схему") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { node ->
        ExpertRuleEditor(node, tree, state, onIntent, { editing = null }, pendingPolicy != null) { updated ->
            val policy = ExpertPolicyEditing.ensureTargetTree(ExpertPolicyEditing.putNode(state.draft, tree.scope, updated), updated.target)
            commitEditor(policy) { editing = null }
        }
    }
    deleting?.let { node ->
        val descendants = ExpertPolicyEditing.descendants(tree, node.id).size - 1
        ExpertConfirm(
            "Удалить «${node.title.ifBlank { "Правило" }}»?",
            if (descendants > 0) {
                "Будут удалены и дочерние правила: $descendants. Изменение останется в черновике до сохранения."
            } else {
                "Изменение останется в черновике. Если это последний ребёнок, родитель снова станет конечным правилом."
            },
            "Удалить", {
                editPolicy(ExpertPolicyEditing.deleteNode(state.draft, tree.scope, node.id))
                selectedId = null
                deleting = null
            }, { deleting = null }
        )
    }
    editingChannel?.let { channel ->
        ExpertChannelEditor(channel, state, { editingChannel = null }, { changed ->
            commitEditor(ExpertPolicyEditing.putChannel(state.draft, changed)) {
                editingChannel = null
            }
        }, {
            if (ExpertPolicyEditing.channelReferenceCount(state.draft, channel.id) == 0) {
                editPolicy(ExpertPolicyEditing.deleteChannel(state.draft, channel.id))
                editingChannel = null
            }
        }, pendingPolicy != null)
    }
    if (folderSettings) {
        (state.selectedScope as? PolicyScope.Folder)?.let { scope ->
            ExpertFolderEditor(scope.id, state, { folderSettings = false }, pendingPolicy != null) { changed ->
                commitEditor(
                    state.draft.copy(
                        folderPolicies = state.draft.folderPolicies.filterNot {
                            it.folderId == changed.folderId
                        } + changed
                    )
                ) {
                    folderSettings = false
                }
            }
        }
    }
    if (profileSettings) {
        (state.selectedScope as? PolicyScope.Profile)?.let { scope ->
            ExpertProfileLifecycleEditor(scope.id, state, { profileSettings = false }, pendingPolicy != null) { changed ->
                commitEditor(
                    state.draft.copy(
                        profilePolicies = state.draft.profilePolicies.filterNot {
                            it.profileId == changed.profileId
                        } + changed
                    )
                ) {
                    profileSettings = false
                }
            }
        }
    }
}

@Composable
private fun ExpertRouteList(
    tree: PolicyTree,
    state: ExpertUiState,
    selectedId: String?,
    modifier: Modifier,
    select: (PolicyNode) -> Unit,
    edit: (PolicyNode) -> Unit,
    addChild: (PolicyNode) -> Unit,
    delete: (PolicyNode) -> Unit,
    channelEdit: (PolicyChannel) -> Unit,
    rootSelect: () -> Unit,
    addRoot: () -> Unit,
) {
    val ordered = remember(tree.nodes) {
        val children = tree.nodes.groupBy { it.parentId }
        val output = mutableListOf<Pair<PolicyNode, Int>>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<Pair<PolicyNode, Int>>()
        children[null].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id }).reversed()
            .forEach { pending.addLast(it to 0) }
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (!visited.add(node.id)) continue
            output += node to depth
            children[node.id].orEmpty().sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id }).reversed()
                .forEach { pending.addLast(it to depth + 1) }
        }
        tree.nodes.filter { it.id !in visited }.forEach { if (visited.add(it.id)) output += it to 0 }
        output
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ExpertPanel("Весь трафик", trailing = { TextButton(onClick = rootSelect) { Text("Выбрать корень") } }) {
                Text("Проверка условий → ИНАЧЕ", color = ExpertColors.blue)
                TextButton(onClick = addRoot) { Text("+ Дочернее правило") }
            }
        }
        if (ordered.isEmpty()) {
            item {
                ExpertEmpty(
                    "Пока нет правил",
                    "Действует путь по умолчанию. Добавьте правило, чтобы изменить его для части трафика."
                )
            }
        }
        items(ordered, key = { it.first.id }) { (node, depth) ->
            val reason = ExpertPolicyEditing.inactiveReason(node, tree, state)
            val channel = (node.target as? PolicyTarget.Channel)?.let { target -> state.draft.channels.firstOrNull { it.id == target.id } }
            Card(
                Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(6) * 18).dp).clickable { select(node) }
                    .semantics { this.selected = node.id == selectedId },
                colors = CardDefaults.cardColors(containerColor = ExpertColors.panel),
                border = BorderStroke(1.dp, if (node.id == selectedId) ExpertColors.blue else ExpertColors.border),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            policyNodeTitle(node), Modifier.weight(1f), color = ExpertColors.text,
                            fontWeight = FontWeight.Medium
                        )
                        if (node.protected || ExpertPolicyEditing.inheritedProtection(tree, node.parentId)) {
                            ExpertTag(
                                if (reason != null) "Защита здесь не действует" else "Только туннель",
                                if (reason != null) ExpertColors.muted else ExpertColors.green,
                            )
                        }
                        if (!node.enabled || node.detached || reason != null) ExpertTag("Неактивно", ExpertColors.muted)
                        if (channel != null && ExpertPolicyEditing.channelReferenceCount(state.draft, channel.id) > 1) {
                            val references = ExpertPolicyEditing.channelReferenceCount(state.draft, channel.id)
                            ExpertTag("Канал · $references входов", ExpertColors.amber)
                        }
                    }
                    Text(ExpertPolicyEditing.conditionSummary(node), color = ExpertColors.muted, fontSize = 13.sp)
                    Text(
                        policyNodeTargetLabel(node, tree, state),
                        color =
                        if (channel != null) ExpertColors.amber else ExpertColors.blue
                    )
                    reason?.let { Text(it, color = ExpertColors.muted, fontSize = 12.sp) }
                    if (node.detached) {
                        Text(
                            "Ветка сохранена для редактирования, но не участвует в маршрутизации.",
                            color = ExpertColors.muted, fontSize = 12.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { edit(node) }) { Text("Свойства") }
                        if (canAddPolicyChild(node)) {
                            TextButton(onClick = { addChild(node) }) { Text("Добавить ребёнка") }
                        }
                        channel?.let { TextButton(onClick = { channelEdit(it) }) { Text("Открыть канал") } }
                        if (!PolicyOtherwise.isOtherwise(node)) {
                            TextButton(onClick = { delete(node) }) { Text("Удалить", color = ExpertColors.red) }
                        }
                    }
                }
            }
        }
        state.draft.channels.filter { it.owner == tree.scope }.distinctBy { it.id }.forEach { channel ->
            item(key = "channel_${channel.id}") {
                ExpertPanel("Канал: ${channel.name}", trailing = { TextButton(onClick = { channelEdit(channel) }) { Text("Свойства") } }) {
                    val references = tree.nodes.filter { it.target == PolicyTarget.Channel(channel.id) }
                        .joinToString { it.title.ifBlank { "Без названия" } }.ifBlank { "нет" }
                    Text(
                        "Входящие ветки: $references",
                        color = ExpertColors.muted, fontSize = 13.sp
                    )
                    Text(ExpertPolicyEditing.targetName(channel.target, state), color = ExpertColors.amber)
                }
            }
        }
    }
}

@Composable
internal fun ExpertGraph(
    sourceTree: PolicyTree,
    state: ExpertUiState,
    selectedId: String?,
    modifier: Modifier,
    select: (PolicyNode) -> Unit,
    channelEdit: (PolicyChannel) -> Unit,
    defaultEdit: () -> Unit,
    addChild: ((PolicyNode) -> Unit)? = null,
    addRoot: (() -> Unit)? = null,
    highlightedNodeIds: Set<String> = emptySet(),
    selectedRoot: Boolean = false,
    highlightedRoot: Boolean = false,
    highlightedDefaultTarget: Boolean = false,
    possiblePath: Boolean = false,
    readOnly: Boolean = false,
    updateLayout: (Map<String, PolicyCanvasPoint>, Boolean) -> Unit = { _, _ -> },
) {
    val tree = PolicyBranchEditing.displayTree(sourceTree)
    val defaultNode = tree.nodes.first { it.parentId == null && !it.detached && PolicyOtherwise.isOtherwise(it) }
    val highlightedNodes = if (highlightedDefaultTarget) highlightedNodeIds + defaultNode.id else highlightedNodeIds
    val pathTone = if (possiblePath) ExpertColors.amber else ExpertColors.green
    val density = LocalDensity.current.density
    val positions = remember(tree.scope) { mutableStateMapOf<String, Offset>() }
    var draggingKey by remember(tree.scope) { mutableStateOf<String?>(null) }
    var aligning by remember(tree.scope) { mutableStateOf(false) }
    LaunchedEffect(tree.positions) {
        if (draggingKey == null) positions.clear()
        aligning = false
    }
    fun position(key: String, automatic: Offset): Offset = positions[key]
        ?: tree.positions[key]?.takeIf { !aligning && it.isValid() }?.let { Offset(it.x, it.y) } ?: automatic
    fun drag(key: String, point: Offset, delta: Offset, transform: RouteCanvasTransform) {
        val current = positions[key] ?: point
        val next = current + transform.canvasDelta(delta, density)
        positions[key] = Offset(
            next.x.coerceIn(-PolicyCanvasPoint.MAX_COORDINATE, PolicyCanvasPoint.MAX_COORDINATE),
            next.y.coerceIn(-PolicyCanvasPoint.MAX_COORDINATE, PolicyCanvasPoint.MAX_COORDINATE)
        )
    }
    fun commitDrag() {
        val point = draggingKey?.let { key -> positions[key]?.let { key to PolicyCanvasPoint(it.x, it.y) } }
        if (point != null) updateLayout(mapOf(point), false)
        draggingKey = null
    }
    fun cancelDrag() {
        draggingKey?.let { positions.remove(it) }
        draggingKey = null
    }
    val nodes = tree.nodes.sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
    val layout = remember(nodes) { RouteTreeLayout.vertical(nodes.map { RouteLayoutNode(it.id, it.parentId) }, 274f, 146f) }
    val pointMap = layout.nodes.mapValues { (id, point) -> position(PolicyCanvasKeys.node(id), Offset(point.x, point.y)) }
    val root = position(PolicyCanvasKeys.ROOT, Offset(layout.root.x, layout.root.y))
    val channels = state.draft.channels.filter { it.owner == tree.scope }.distinctBy { it.id }
    val highlightedChannels = mutableSetOf<String>()
    fun collectChannels(target: PolicyTarget) {
        var current = target
        while (current is PolicyTarget.Channel && highlightedChannels.add(current.id)) {
            val channelId = current.id
            current = channels.firstOrNull { it.id == channelId }?.target ?: break
        }
    }
    nodes.filter { it.id in highlightedNodes }.forEach { collectChannels(it.target) }
    val channelTop = (pointMap.values.maxOfOrNull { it.y } ?: root.y) + 146f
    val channelPoints = channels.mapIndexed { index, channel ->
        channel.id to position(PolicyCanvasKeys.channel(channel.id), Offset(40f + index * 274f, channelTop))
    }.toMap()
    val points = pointMap.values + channelPoints.values + root
    val bounds = Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x } + 304f, points.maxOf { it.y } + 134f)
    RouteEditorCanvas(
        tree.scope, bounds, modifier.fillMaxSize(),
        onArrange = if (readOnly) {
            null
        } else {
            fun() {
                positions.clear()
                aligning = true
                updateLayout(emptyMap(), true)
            }
        },
        connections = { transform ->
            fun px(point: Offset) = transform.project(point, density)
            fun line(from: Offset, to: Offset, lane: Int = 0, highlighted: Boolean = false) {
                val start = px(from + Offset(105f, 76f))
                val end = px(to + Offset(105f, 0f))
                val middle = if (end.y > start.y + 20.dp.toPx()) {
                    (start.y + end.y) / 2f + lane * 3.dp.toPx()
                } else {
                    maxOf(start.y, end.y) + 18.dp.toPx() + lane * 8.dp.toPx()
                }
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    lineTo(start.x, middle)
                    lineTo(end.x, middle)
                    lineTo(end.x, end.y)
                }
                drawPath(
                    path, if (highlighted) pathTone else ExpertColors.border,
                    style = Stroke(
                        (if (highlighted) 2.5.dp else 1.5.dp).toPx(),
                        pathEffect = if (highlighted && possiblePath) {
                            PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                        } else {
                            null
                        }
                    )
                )
            }
            nodes.forEach { node ->
                pointMap[node.id]?.let { line(node.parentId?.let { pointMap[it] } ?: root, it, highlighted = node.id in highlightedNodes) }
            }
            nodes.forEachIndexed { index, node ->
                val destination = (node.target as? PolicyTarget.Channel)?.id?.let { channelPoints[it] }
                if (destination != null) pointMap[node.id]?.let { line(it, destination, index % 6, node.id in highlightedNodes) }
            }
            channels.forEachIndexed { index, channel ->
                (channel.target as? PolicyTarget.Channel)?.id?.let { channelPoints[it] }?.let {
                    line(channelPoints.getValue(channel.id), it, index, channel.id in highlightedChannels)
                }
            }
        }, content = { transform ->
            GraphCard(
                root, transform, "Весь трафик", "Корень маршрутизации", selectedRoot, false, null,
                defaultEdit, { drag(PolicyCanvasKeys.ROOT, root, it, transform) },
                target = "Проверка условий → ИНАЧЕ",
                highlightTone = pathTone, onAdd = addRoot, draggable = !readOnly,
                highlighted = highlightedRoot || highlightedNodes.isNotEmpty(),
                onDragStart = { draggingKey = PolicyCanvasKeys.ROOT }, onDragEnd = ::commitDrag, onDragCancel = ::cancelDrag
            )
            nodes.forEach { node ->
                val point = pointMap.getValue(node.id)
                GraphCard(
                    point, transform, policyNodeTitle(node), ExpertPolicyEditing.conditionSummary(node),
                    selectedId == node.id, false,
                    ExpertPolicyEditing.inactiveReason(node, tree, state)
                        ?: if (!node.enabled) {
                            "Выключена"
                        } else if (node.detached) {
                            "Отсоединена"
                        } else {
                            null
                        },
                    { select(node) }, { drag(PolicyCanvasKeys.node(node.id), point, it, transform) },
                    target = policyNodeTargetLabel(node, tree, state),
                    onAdd = if (canAddPolicyChild(node) && addChild != null) {
                        fun() {
                            addChild(node)
                        }
                    } else {
                        null
                    },
                    highlightTone = pathTone, draggable = !readOnly, highlighted = node.id in highlightedNodes,
                    onDragStart = { draggingKey = PolicyCanvasKeys.node(node.id) },
                    onDragEnd = ::commitDrag, onDragCancel = ::cancelDrag
                )
            }
            channels.forEach { channel ->
                val point = channelPoints.getValue(channel.id)
                GraphCard(
                    point, transform, "Канал: ${channel.name}",
                    "Входов: ${ExpertPolicyEditing.channelReferenceCount(state.draft, channel.id)}", false, true, null,
                    { channelEdit(channel) }, { drag(PolicyCanvasKeys.channel(channel.id), point, it, transform) },
                    target = ExpertPolicyEditing.targetName(channel.target, state),
                    draggable = !readOnly,
                    highlightTone = pathTone, highlighted = channel.id in highlightedChannels,
                    onDragStart = { draggingKey = PolicyCanvasKeys.channel(channel.id) },
                    onDragEnd = ::commitDrag, onDragCancel = ::cancelDrag
                )
            }
        }
    )
}

@Composable
private fun GraphCard(
    point: Offset,
    transform: RouteCanvasTransform,
    title: String,
    description: String,
    selected: Boolean,
    channel: Boolean,
    inactive: String?,
    onClick: () -> Unit,
    onDrag: (Offset) -> Unit,
    target: String = "Путь по умолчанию",
    onDragEnd: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onAdd: (() -> Unit)? = null,
    draggable: Boolean = true,
    highlighted: Boolean = false,
    highlightTone: androidx.compose.ui.graphics.Color = ExpertColors.green,
) {
    val tone = when {
        inactive != null -> ExpertColors.muted
        channel -> ExpertColors.amber
        else -> ExpertColors.blue
    }
    RouteEditorPositioned(point, transform) {
        RouteEditorNodeActions(selected, onAdd) {
            RouteEditorNodeFace(
                title, inactive ?: description, target, tone, selected,
                Modifier.then(
                    if (draggable) {
                        Modifier.routeEditorDrag(title, transform.zoom, { onDragStart() }, onDrag, onDragEnd, onDragCancel)
                    } else {
                        Modifier
                    }
                )
                    .then(if (draggable) Modifier.clickable(onClick = onClick) else Modifier).semantics {
                        this.selected = selected
                        contentDescription = "$title. $description. $target.${inactive?.let { " Неактивно: $it" }.orEmpty()}"
                    },
                muted = inactive != null, channel = channel, highlighted = highlighted, highlightTone = highlightTone
            )
        }
    }
}

private fun canAddPolicyChild(node: PolicyNode): Boolean = PolicyOtherwise.isOtherwise(node) ||
    (node.target !is PolicyTarget.Profile && node.target !is PolicyTarget.Folder && node.target !is PolicyTarget.Channel)

private fun policyNodeTitle(node: PolicyNode): String = if (PolicyOtherwise.isOtherwise(node)) {
    node.title.takeIf { it.isNotBlank() && it != "ИНАЧЕ" }?.let { "ИНАЧЕ · $it" } ?: "ИНАЧЕ"
} else {
    node.title.ifBlank { "Без названия" }
}

private fun policyNodeTargetLabel(node: PolicyNode, tree: PolicyTree, state: ExpertUiState): String =
    if (tree.nodes.any { it.parentId == node.id && !it.detached }) {
        "Продолжить по дочерним веткам"
    } else {
        ExpertPolicyEditing.targetName(node.target, state)
    }

private fun canMovePolicyNode(tree: PolicyTree, node: PolicyNode, delta: Int): Boolean {
    if (PolicyOtherwise.isOtherwise(node)) return false
    val siblings = tree.nodes.filter { it.parentId == node.parentId }.sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
    val destination = siblings.getOrNull(siblings.indexOfFirst { it.id == node.id } + delta)
    return destination != null && !PolicyOtherwise.isOtherwise(destination)
}

private fun canAddMissingOtherwise(tree: PolicyTree, parent: PolicyNode): Boolean {
    val children = tree.nodes.filter { it.parentId == parent.id && !it.detached }
    return children.isNotEmpty() && children.none { PolicyOtherwise.isOtherwise(it) }
}
