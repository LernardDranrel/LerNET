package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.window.Dialog
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
import app.lernet.desktop.RouteEditorNodeActions
import app.lernet.desktop.RouteEditorNodeFace
import app.lernet.desktop.RouteEditorPositioned
import app.lernet.desktop.RouteEditorView
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
    requestedDeviceRule: String? = null,
    onRuleOpened: () -> Unit = {},
) {
    val tree = remember(state.draft, state.selectedScope) {
        PolicyBranchEditing.displayTree(ExpertPolicyEditing.tree(state.draft, state.selectedScope))
    }
    val rootOtherwise = tree.nodes.first { it.parentId == null && !it.detached && PolicyOtherwise.isOtherwise(it) }
    val branches = remember(tree.nodes) { DesktopRuleBranches(tree) }
    var view by rememberSaveable { mutableStateOf(RouteEditorView.SCHEME) }
    var fullMap by rememberSaveable(state.selectedScope) { mutableStateOf(false) }
    var focusId by rememberSaveable(state.selectedScope) { mutableStateOf<String?>(null) }
    var expandedIds by rememberSaveable(state.selectedScope) { mutableStateOf(emptyList<String>()) }
    var collapsedOrigin by rememberSaveable(state.selectedScope) { mutableStateOf(false) }
    var cameraRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var mapMenu by remember { mutableStateOf(false) }
    var inspectorOpen by rememberSaveable { mutableStateOf(false) }
    var query by remember(state.selectedScope) { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var selectedId by rememberSaveable(state.selectedScope) { mutableStateOf(branches.children[null]?.firstOrNull()?.id) }
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
    val selected = branches.byId[selectedId]
    fun go(id: String?, selectId: String? = id) {
        focusId = id
        expandedIds = emptyList()
        collapsedOrigin = false
        selectedId = selectId
        cameraRequest++
    }
    LaunchedEffect(requestedDeviceRule, state.selectedScope) {
        if (requestedDeviceRule == null) return@LaunchedEffect
        if (state.selectedScope != PolicyScope.Device) {
            onIntent(ExpertIntent.SelectScope(PolicyScope.Device))
        } else {
            tree.nodes.find { it.id == requestedDeviceRule }?.let {
                selectedId = it.id
                editing = it
            }
            onRuleOpened()
        }
    }
    val graphProblem = remember(tree) { ExpertPolicyEditing.graphProblem(tree) }
    val actualFocus = focusId?.takeIf { it in branches.byId }
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
    val nodeSelect: (PolicyNode) -> Unit = { node -> selectedId = node.id; inspectorOpen = true }
    fun selectRoot() { selectedId = null; inspectorOpen = true }
    fun changeFocus(id: String?) {
        go(id, branches.children[id]?.firstOrNull()?.id ?: id)
        fullMap = false
        view = RouteEditorView.SCHEME
    }
    BoxWithConstraints(modifier.focusRequester(focus).onPreviewKeyEvent { event ->
        when {
            event.type != KeyEventType.KeyDown || searching || editing != null || deleting != null || editingChannel != null -> false
            event.key == Key.Slash -> { searchFocus.requestFocus(); true }
            event.key == Key.Delete && selected != null && !PolicyOtherwise.isOtherwise(selected) && !folderSettings && !profileSettings -> {
                deleting = selected
                true
            }
            else -> false
        }
    }.focusable()) {
        val small = maxWidth < 480.dp
        val narrow = maxWidth < 790.dp
        val inspectorWidth = if (maxWidth < 1060.dp) 218.dp else 262.dp
        val effectiveView = if (graphProblem == null) view else RouteEditorView.LIST
        val selectFromSearch: (PolicyNode) -> Unit = { node ->
            query = ""
            go(branches.path(node.id).dropLast(1).lastOrNull()?.id, node.id)
            fullMap = false
            view = RouteEditorView.SCHEME
            inspectorOpen = true
            focus.requestFocus()
        }
        val inspector: @Composable (Modifier) -> Unit = { panelModifier ->
            val node = selected
            val children = branches.children[node?.id].orEmpty()
            RuleInspector(node?.let(::policyNodeTitle) ?: "Весь трафик",
                if (children.isNotEmpty()) "Выбранная ветка" else "Выбранное правило", panelModifier,
                if (narrow) ({ inspectorOpen = false }) else null) {
                if (node != null) {
                    InspectorValue("Условие", ExpertPolicyEditing.conditionSummary(node))
                    if (children.isEmpty()) InspectorValue("Направить через", ExpertPolicyEditing.targetName(node.target, state))
                    ExpertPolicyEditing.inactiveReason(node, tree, state)?.let { InspectorValue("Состояние", it) }
                }
                if (children.isNotEmpty()) InspectorValue("Внутри", "Правил · ${children.size}")
                if (node == null) InspectorValue("По умолчанию", ExpertPolicyEditing.targetName(rootOtherwise.target, state))
                HorizontalDivider(color = ExpertColors.border)
                if (node != null && children.isNotEmpty() && node.id != actualFocus) {
                    MapButton("В фокус", { changeFocus(node.id); inspectorOpen = false }, primary = true)
                }
                MapButton(if (node == null) "Настроить ИНАЧЕ" else "Редактировать",
                    { editing = node ?: rootOtherwise; inspectorOpen = false }, primary = node != null && children.isEmpty())
                if (node == null || canAddPolicyChild(node)) {
                    MapButton("+ Дочернее правило", { newNode(node); inspectorOpen = false })
                }
                if (node != null) {
                    val referencedScope = when (val target = node.target) {
                        is PolicyTarget.Profile -> target.routeScope
                        is PolicyTarget.Folder -> target.routeScope
                        else -> null
                    }
                    if (referencedScope != null) MapButton("Открыть дочернюю схему", { onIntent(ExpertIntent.SelectScope(referencedScope)) })
                    var more by remember(node.id) { mutableStateOf(false) }
                    if (!PolicyOtherwise.isOtherwise(node) || canAddMissingOtherwise(tree, node)) Box {
                        MapButton("Действия ▾", { more = true })
                        DropdownMenu(more, { more = false }) {
                            if (canAddMissingOtherwise(tree, node)) DropdownMenuItem(text = { Text("Добавить ИНАЧЕ") },
                                onClick = { more = false; newOtherwise(node); inspectorOpen = false })
                            if (!PolicyOtherwise.isOtherwise(node)) {
                                listOf(-1 to "Выше", 1 to "Ниже").forEach { (delta, label) ->
                                    DropdownMenuItem(text = { Text(label) }, enabled = canMovePolicyNode(tree, node, delta),
                                        onClick = { more = false; editPolicy(ExpertPolicyEditing.moveNode(state.draft, tree.scope, node.id, delta)) })
                                }
                                DropdownMenuItem(text = { Text(if (node.enabled) "Выключить ветку" else "Включить ветку") },
                                    onClick = { more = false; editPolicy(ExpertPolicyEditing.putNode(state.draft, tree.scope, node.copy(enabled = !node.enabled))) })
                                DropdownMenuItem(text = { Text("Удалить", color = ExpertColors.red) },
                                    onClick = { more = false; deleting = node; inspectorOpen = false })
                            }
                        }
                    }
                }
                HorizontalDivider(color = ExpertColors.border)
                InspectorTrail(branches.path(node?.id)) { changeFocus(it); inspectorOpen = false }
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val title: @Composable () -> Unit = {
                Column {
                    Text("Маршрутизация", color = ExpertColors.muted, fontSize = 12.sp)
                    Text("Схема правил", color = ExpertColors.text, fontSize = if (narrow) 24.sp else 30.sp,
                        lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (small) title()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!small) Box(Modifier.weight(1f)) { title() }
                MapSearch(branches, if (small) Modifier.weight(1f) else Modifier.width(if (narrow) 155.dp else 205.dp),
                    searchFocus, query, { query = it }, { searching = it }, selectFromSearch)
                val parent = branches.byId[actualFocus]
                MapButton(if (narrow) "+ Правило" else "+ Добавить правило", { newNode(parent) },
                    primary = true, enabled = !state.busy && (parent == null || canAddPolicyChild(parent)))
            }
            graphProblem?.let { ExpertMessage("Схема показана списком", it, warning = true) }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ExpertColors.border), color = ExpertColors.panel) {
                    Column(Modifier.fillMaxSize().padding(1.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            MapBreadcrumbs(branches.path(actualFocus), ::changeFocus, Modifier.weight(1f))
                            if (!fullMap && graphProblem == null && effectiveView == RouteEditorView.SCHEME && !narrow) {
                                val expandable = remember(branches, actualFocus) {
                                    branches.visible(actualFocus, branches.children.keys.filterNotNull().toSet())
                                        .mapNotNull { it.id.takeIf(branches.children::containsKey) }
                                }
                                if (expandable.isNotEmpty()) {
                                    val opened = !collapsedOrigin && expandedIds.containsAll(expandable)
                                    MapButton(if (opened) "Свернуть всё" else "Раскрыть всё", {
                                        expandedIds = if (opened) emptyList() else expandable
                                        collapsedOrigin = false
                                        if (opened) selectedId = actualFocus
                                        cameraRequest++
                                    })
                                }
                            }
                            Box {
                                MapButton(when { effectiveView == RouteEditorView.LIST -> "Список ▾"; fullMap -> "Полная схема ▾"; else -> "Граф ▾" },
                                    { mapMenu = true })
                                DropdownMenu(mapMenu, { mapMenu = false }) {
                                    DropdownMenuItem(text = { Text("Граф веток") }, enabled = graphProblem == null,
                                        onClick = { mapMenu = false; fullMap = false; view = RouteEditorView.SCHEME })
                                    DropdownMenuItem(text = { Text("Списком") }, onClick = { mapMenu = false; view = RouteEditorView.LIST })
                                    DropdownMenuItem(text = { Text("Полная схема и каналы") }, enabled = graphProblem == null,
                                        onClick = { mapMenu = false; fullMap = true; view = RouteEditorView.SCHEME; go(null) })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Упорядочить узлы") }, enabled = tree.positions.isNotEmpty() && !state.busy,
                                        onClick = { mapMenu = false; onIntent(ExpertIntent.UpdateLayout(tree.scope, emptyMap(), true)); cameraRequest++ })
                                    DropdownMenuItem(text = { Text("Новый канал") }, enabled = !state.busy, onClick = {
                                        mapMenu = false
                                        editingChannel = PolicyChannel(UUID.randomUUID().toString(), "", tree.scope,
                                            target = if (tree.scope == PolicyScope.Device) PolicyTarget.Direct else PolicyTarget.CurrentExit)
                                    })
                                    if (state.selectedScope is PolicyScope.Folder) DropdownMenuItem(text = { Text("Политика папки") }, onClick = { mapMenu = false; folderSettings = true })
                                    if (state.selectedScope is PolicyScope.Profile) DropdownMenuItem(text = { Text("Холодный старт") }, onClick = { mapMenu = false; profileSettings = true })
                                }
                            }
                            ScopePicker(state) { onIntent(ExpertIntent.SelectScope(it)) }
                        }
                        HorizontalDivider(color = ExpertColors.border)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                effectiveView == RouteEditorView.LIST -> ExpertRouteList(tree, state, selectedId, Modifier.fillMaxSize(),
                                    nodeSelect, { editing = it }, { newNode(it) }, { deleting = it }, { editingChannel = it }, ::selectRoot, { newNode() })
                                fullMap -> ExpertGraph(tree, state, selectedId, Modifier.fillMaxSize(), nodeSelect, { editingChannel = it },
                                    ::selectRoot, { newNode(it) }, { newNode() }, highlightedNodeIds = highlightedNodeIds, selectedRoot = selected == null,
                                    updateLayout = { points, clear -> onIntent(ExpertIntent.UpdateLayout(tree.scope, points, clear)) })
                                else -> ExpertBranchMap(tree, branches, state, actualFocus, selectedId, expandedIds.toSet(), collapsedOrigin, cameraRequest,
                                    select = { if (it == null) selectRoot() else nodeSelect(it) },
                                    fold = { id ->
                                        if (id == actualFocus) {
                                            if (!collapsedOrigin) selectedId = actualFocus
                                            collapsedOrigin = !collapsedOrigin
                                        } else if (id != null) {
                                            val closing = id in expandedIds
                                            if (closing && branches.path(selectedId).any { it.id == id }) selectedId = id
                                            expandedIds = if (closing) expandedIds - id else expandedIds + id
                                        }
                                    },
                                    move = { key, point -> onIntent(ExpertIntent.UpdateLayout(tree.scope, mapOf(key to point), false)) },
                                    modifier = Modifier.fillMaxSize())
                            }
                        }
                        HorizontalDivider(color = ExpertColors.border)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Правил в схеме · ${tree.nodes.size}", Modifier.weight(1f), color = ExpertColors.muted, fontSize = 12.sp)
                            if (actualFocus != null) MapButton("Вся схема", { changeFocus(null) })
                            if (narrow) MapButton("Свойства", { inspectorOpen = true })
                        }
                    }
                }
                if (!narrow) inspector(Modifier.width(inspectorWidth).wrapContentHeight())
            }
        }
        if (narrow && inspectorOpen && editing == null && deleting == null) {
            Dialog(onDismissRequest = { inspectorOpen = false }) { inspector(Modifier.widthIn(max = 390.dp).fillMaxWidth()) }
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
                    "Действует путь по умолчанию. Добавь правило, чтобы изменить его для части трафика."
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
                root, transform, app.lernet.engine.text.SchemaWords.ROOT_TITLE, app.lernet.engine.text.SchemaWords.ROOT_SUBTITLE, selectedRoot, false, null,
                defaultEdit, { drag(PolicyCanvasKeys.ROOT, root, it, transform) },
                target = app.lernet.engine.text.SchemaWords.BRANCHING,
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

internal fun policyNodeTitle(node: PolicyNode): String = if (PolicyOtherwise.isOtherwise(node)) {
    node.title.takeIf { it.isNotBlank() && it != "ИНАЧЕ" }?.let { "ИНАЧЕ · $it" } ?: "ИНАЧЕ"
} else {
    node.title.ifBlank { "Без названия" }
}

internal fun policyNodeTargetLabel(node: PolicyNode, tree: PolicyTree, state: ExpertUiState): String =
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
