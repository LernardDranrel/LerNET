package app.lernet.desktop.expert

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lernet.desktop.RouteCanvasTransform
import app.lernet.desktop.RouteEditorCanvas
import app.lernet.desktop.RouteEditorNodeActions
import app.lernet.desktop.RouteEditorNodeFace
import app.lernet.desktop.RouteEditorPositioned
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
    val selected = branches.byId[selectedId]
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
    DesktopBranchWorkspace(
        tree, branches, selectedId, { selectedId = it?.id },
        targetName = { ExpertPolicyEditing.targetName(it.target, state) },
        inactiveReason = { ExpertPolicyEditing.inactiveReason(it, tree, state) },
        canAdd = { !state.busy && (it == null || canAddPolicyChild(it)) }, add = ::newNode,
        move = { key, point -> onIntent(ExpertIntent.UpdateLayout(tree.scope, mapOf(key to point))) },
        arrange = { onIntent(ExpertIntent.UpdateLayout(tree.scope, emptyMap(), true)) }, modifier = modifier,
        layoutEnabled = !state.busy, overlayOpen = editing != null || deleting != null || editingChannel != null || folderSettings || profileSettings,
        deleteSelected = selected?.takeUnless(PolicyOtherwise::isOtherwise)?.let { { deleting = it } },
        menuItems = { close ->
            DropdownMenuItem(text = { Text("Новый канал") }, enabled = !state.busy, onClick = {
                close()
                editingChannel = PolicyChannel(UUID.randomUUID().toString(), "", tree.scope,
                    target = if (tree.scope == PolicyScope.Device) PolicyTarget.Direct else PolicyTarget.CurrentExit)
            })
            if (state.selectedScope is PolicyScope.Folder) DropdownMenuItem(text = { Text("Политика папки") }, onClick = { close(); folderSettings = true })
            if (state.selectedScope is PolicyScope.Profile) DropdownMenuItem(text = { Text("Холодный старт") }, onClick = { close(); profileSettings = true })
        },
        scopePicker = { ScopePicker(state) { onIntent(ExpertIntent.SelectScope(it)) } },
        reorder = { node, delta -> editPolicy(ExpertPolicyEditing.moveNode(state.draft, tree.scope, node.id, delta)) },
        listBadges = { node ->
            if (node.protected || ExpertPolicyEditing.inheritedProtection(tree, node.parentId)) {
                val inactive = ExpertPolicyEditing.inactiveReason(node, tree, state) != null
                ExpertTag(if (inactive) "Защита здесь не действует" else "Только туннель",
                    if (inactive) ExpertColors.muted else ExpertColors.green)
            }
        },
        listExtras = {
            state.draft.channels.filter { it.owner == tree.scope }.distinctBy { it.id }.forEach { channel ->
                item(key = PolicyCanvasKeys.channel(channel.id)) {
                    val sources = tree.nodes.filter { it.target == PolicyTarget.Channel(channel.id) }.joinToString { policyNodeTitle(it) }
                    BranchListChannel(channel.name, sources, ExpertPolicyEditing.targetName(channel.target, state)) { editingChannel = channel }
                }
            }
        },
        fullView = { select ->
            ExpertGraph(tree, state, selectedId, Modifier.fillMaxSize(), { select(it) }, { editingChannel = it },
                { select(null) }, { newNode(it) }, { newNode() }, highlightedNodeIds = highlightedNodeIds,
                selectedRoot = selected == null, updateLayout = { points, clear -> onIntent(ExpertIntent.UpdateLayout(tree.scope, points, clear)) })
        },
        inspector = { panelModifier, close, actualFocus, changeFocus ->
            val node = selected
            BranchRuleInspector(node, branches, panelModifier, close, actualFocus, changeFocus,
                status = node?.let { ExpertPolicyEditing.inactiveReason(it, tree, state) },
                destination = ExpertPolicyEditing.targetName(node?.target ?: rootOtherwise.target, state),
                onEdit = { editing = node ?: rootOtherwise },
                onAdd = if (node == null || canAddPolicyChild(node)) ({ newNode(node) }) else null,
            ) {
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
                                onClick = { more = false; newOtherwise(node); close?.invoke() })
                            if (!PolicyOtherwise.isOtherwise(node)) {
                                listOf(-1 to "Выше", 1 to "Ниже").forEach { (delta, label) ->
                                    DropdownMenuItem(text = { Text(label) }, enabled = canMovePolicyNode(tree, node, delta),
                                        onClick = { more = false; editPolicy(ExpertPolicyEditing.moveNode(state.draft, tree.scope, node.id, delta)) })
                                }
                                DropdownMenuItem(text = { Text(if (node.enabled) "Выключить ветку" else "Включить ветку") },
                                    onClick = { more = false; editPolicy(ExpertPolicyEditing.putNode(state.draft, tree.scope, node.copy(enabled = !node.enabled))) })
                                DropdownMenuItem(text = { Text("Удалить", color = ExpertColors.red) },
                                    onClick = { more = false; deleting = node; close?.invoke() })
                            }
                        }
                    }
                }

            }
        },
    )
    editing?.let { node ->
        ExpertRuleEditor(node, tree, state, onIntent, { editing = null }, pendingPolicy != null) { updated ->
            val policy = ExpertPolicyEditing.ensureTargetTree(ExpertPolicyEditing.putNode(state.draft, tree.scope, updated), updated.target)
            commitEditor(policy) { selectedId = updated.id; editing = null }
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
    node.title.takeIf { it.isNotBlank() && !it.trim().equals("ИНАЧЕ", true) }?.let { "ИНАЧЕ · $it" } ?: "ИНАЧЕ"
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
