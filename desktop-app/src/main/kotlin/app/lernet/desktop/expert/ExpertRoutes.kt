package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.routing.RouteLayoutNode
import app.lernet.routing.RouteTreeLayout
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import java.util.UUID
import kotlin.math.roundToInt

private enum class RoutePresentation { GRAPH, LIST }

@Composable
internal fun ExpertRoutes(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, modifier: Modifier) {
    val tree = ExpertPolicyEditing.tree(state.draft, state.selectedScope)
    var view by remember { mutableStateOf(RoutePresentation.GRAPH) }
    var ownerMenu by remember { mutableStateOf(false) }
    var selectedId by remember(state.selectedScope) { mutableStateOf<String?>(null) }
    var editing by remember(state.selectedScope) { mutableStateOf<PolicyNode?>(null) }
    var deleting by remember(state.selectedScope) { mutableStateOf<PolicyNode?>(null) }
    var editingChannel by remember(state.selectedScope) { mutableStateOf<PolicyChannel?>(null) }
    var folderSettings by remember(state.selectedScope) { mutableStateOf(false) }
    var profileSettings by remember(state.selectedScope) { mutableStateOf(false) }
    var defaultTargetEditor by remember(state.selectedScope) { mutableStateOf(false) }
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
            target = if (tree.scope == PolicyScope.Device) PolicyTarget.Direct else PolicyTarget.CurrentExit,
        )
    }
    Column(
        modifier.focusRequester(focus).focusable().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                event.key in setOf(Key.Delete, Key.Backspace) &&
                selected != null &&
                editing == null &&
                deleting == null &&
                editingChannel == null &&
                !folderSettings &&
                !profileSettings &&
                !defaultTargetEditor
            ) {
                deleting = selected
                true
            } else {
                false
            }
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { ownerMenu = true }) { Text(ExpertPolicyEditing.scopeName(state.selectedScope, state)) }
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
            FilterChip(view == RoutePresentation.GRAPH, { view = RoutePresentation.GRAPH }, label = { Text("Схема") })
            FilterChip(view == RoutePresentation.LIST, { view = RoutePresentation.LIST }, label = { Text("Списком") })
            Button(onClick = { newNode() }, enabled = !state.busy) { Text("Добавить правило") }
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Если правило не подошло: ${ExpertPolicyEditing.targetName(tree.defaultTarget, state)}", Modifier.weight(1f),
                color = ExpertColors.muted, fontSize = 13.sp
            )
            TextButton(onClick = { defaultTargetEditor = true }) { Text("Изменить") }
        }
        Text(
            "Сначала проверяются верхние ветки. Условия родителя и ребёнка объединяются через И. " +
                "Каналы с одним идентификатором — один выход; название служит только подписью.",
            color = ExpertColors.muted, fontSize = 12.sp,
        )
        val nodeSelect: (PolicyNode) -> Unit = {
            selectedId = it.id
            focus.requestFocus()
        }
        graphProblem?.let { ExpertMessage("Схема показана списком", it, warning = true) }
        when (if (graphProblem != null) RoutePresentation.LIST else view) {
            RoutePresentation.GRAPH -> ExpertGraph(
                tree, state, selectedId, Modifier.weight(1f), nodeSelect,
                { editing = it }, { newNode(it) }, { editingChannel = it }, { defaultTargetEditor = true }
            ) { updated ->
                editPolicy(ExpertPolicyEditing.replaceTree(state.draft, updated))
            }
            RoutePresentation.LIST -> ExpertRouteList(
                tree, state, selectedId, Modifier.weight(1f), nodeSelect,
                { editing = it }, { newNode(it) }, { deleting = it }, { editingChannel = it }
            )
        }
        selected?.let { node ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = node }) { Text("Свойства") }
                OutlinedButton(onClick = {
                    editPolicy(
                        ExpertPolicyEditing.moveNode(
                            state.draft, tree.scope, node.id,
                            -1
                        )
                    )
                }) { Text("Выше") }
                OutlinedButton(onClick = { editPolicy(ExpertPolicyEditing.moveNode(state.draft, tree.scope, node.id, 1)) }) { Text("Ниже") }
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
    if (defaultTargetEditor) {
        ExpertDefaultTargetEditor(tree, state, { defaultTargetEditor = false }, pendingPolicy != null) { target ->
            commitEditor(
                ExpertPolicyEditing.ensureTargetTree(
                    ExpertPolicyEditing.replaceTree(
                        state.draft,
                        tree.copy(defaultTarget = target)
                    ),
                    target
                )
            ) {
                defaultTargetEditor = false
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
                            node.title.ifBlank { "Без названия" }, Modifier.weight(1f), color = ExpertColors.text,
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
                            ExpertTag("Общий канал", ExpertColors.amber)
                        }
                    }
                    Text(ExpertPolicyEditing.conditionSummary(node), color = ExpertColors.muted, fontSize = 13.sp)
                    Text(
                        ExpertPolicyEditing.targetName(node.target, state),
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
                        if (node.target !is PolicyTarget.Profile &&
                            node.target !is PolicyTarget.Folder &&
                            node.target !is PolicyTarget.Channel
                        ) {
                            TextButton(onClick = { addChild(node) }) { Text("Добавить ребёнка") }
                        }
                        channel?.let { TextButton(onClick = { channelEdit(it) }) { Text("Открыть канал") } }
                        TextButton(onClick = { delete(node) }) { Text("Удалить", color = ExpertColors.red) }
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
private fun ExpertGraph(
    tree: PolicyTree,
    state: ExpertUiState,
    selectedId: String?,
    modifier: Modifier,
    select: (PolicyNode) -> Unit,
    edit: (PolicyNode) -> Unit,
    addChild: (PolicyNode) -> Unit,
    channelEdit: (PolicyChannel) -> Unit,
    defaultEdit: () -> Unit,
    updateLayout: (PolicyTree) -> Unit,
) {
    val density = LocalDensity.current
    val positions = remember(tree.scope) { mutableStateMapOf<String, Offset>() }
    var draggingKey by remember(tree.scope) { mutableStateOf<String?>(null) }
    var aligning by remember(tree.scope) { mutableStateOf(false) }
    var origin by remember(tree.scope) {
        mutableStateOf(
            Offset(
                (12f - (tree.positions.values.filter { it.isValid() }.minOfOrNull { it.x } ?: 40f)).coerceAtLeast(0f),
                (12f - (tree.positions.values.filter { it.isValid() }.minOfOrNull { it.y } ?: 40f)).coerceAtLeast(0f),
            )
        )
    }
    var zoom by remember(tree.scope) { mutableStateOf(1f) }
    var centerAnchor by remember(tree.scope) { mutableStateOf<Float?>(null) }
    var fitted by remember(tree.scope) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    LaunchedEffect(tree.positions) {
        if (draggingKey == null) positions.clear()
        aligning = false
    }
    fun position(key: String, automatic: Offset): Offset = positions[key]
        ?: tree.positions[key]?.takeIf { !aligning && it.isValid() }?.let { Offset(it.x, it.y) } ?: automatic
    fun drag(key: String, point: Offset, delta: Offset) {
        val current = positions[key] ?: point
        positions[key] = Offset(
            (current.x + delta.x / density.density / zoom).coerceIn(12f - origin.x, PolicyCanvasPoint.MAX_COORDINATE),
            (current.y + delta.y / density.density / zoom).coerceIn(12f - origin.y, PolicyCanvasPoint.MAX_COORDINATE),
        )
    }
    fun commitDrag() {
        if (positions.isNotEmpty()) {
            updateLayout(
                tree.copy(
                    positions = tree.positions + positions.mapValues { (_, value) ->
                        PolicyCanvasPoint(value.x, value.y)
                    }
                )
            )
        }
        draggingKey = null
    }
    fun cancelDrag() {
        draggingKey?.let { positions.remove(it) }
        draggingKey = null
    }
    val nodes = tree.nodes.sortedWith(compareBy<PolicyNode> { it.sortIndex }.thenBy { it.id })
    val layout = remember(nodes) { RouteTreeLayout.vertical(nodes.map { RouteLayoutNode(it.id, it.parentId) }, 250f, 200f) }
    val pointMap = layout.nodes.mapValues { (id, point) -> position(PolicyCanvasKeys.node(id), Offset(point.x, point.y)) }
    val root = position(PolicyCanvasKeys.ROOT, Offset(layout.root.x, layout.root.y))
    val channels = state.draft.channels.filter { it.owner == tree.scope }.distinctBy { it.id }
    val channelTop = (pointMap.values.maxOfOrNull { it.y } ?: root.y) + 230f
    val channelPoints = channels.mapIndexed { index, channel ->
        channel.id to
            position(PolicyCanvasKeys.channel(channel.id), Offset(40f + index * 250f, channelTop))
    }.toMap()
    val width = maxOf(760f, (pointMap.values + channelPoints.values + root).maxOf { it.x } + origin.x + 265f)
    val height = maxOf(380f, (pointMap.values + channelPoints.values + root).maxOf { it.y } + origin.y + 195f)
    fun fitZoom(minimum: Float): Float {
        if (viewport.width == 0 || viewport.height == 0) return 1f
        val availableWidth = viewport.width / density.density - 24f
        val availableHeight = viewport.height / density.density - 24f
        return minOf(1f, availableWidth / width, availableHeight / height).coerceAtLeast(minimum)
    }
    LaunchedEffect(viewport, width, height) {
        if (!fitted && viewport.width > 0 && viewport.height > 0) {
            zoom = fitZoom(.7f)
            fitted = true
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                zoom = (zoom - .15f).coerceAtLeast(.35f)
                fitted = true
                centerAnchor = null
            }) { Text("Уменьшить") }
            Text("${(zoom * 100).roundToInt()}%", color = ExpertColors.muted, fontSize = 12.sp)
            TextButton(onClick = {
                zoom = (zoom + .15f).coerceAtMost(1.3f)
                fitted = true
                centerAnchor = null
            }) { Text("Увеличить") }
            TextButton(onClick = {
                zoom = fitZoom(.35f)
                fitted = true
                centerAnchor = null
            }) { Text("Вписать") }
            TextButton(onClick = {
                positions.clear()
                aligning = true
                origin = Offset.Zero
                zoom = 1f
                fitted = false
                centerAnchor = null
                updateLayout(tree.copy(positions = emptyMap()))
            }) { Text("Выровнять") }
            Text("Перетаскивайте за любую часть карточки", color = ExpertColors.muted, fontSize = 12.sp)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it }
                .clip(RoundedCornerShape(16.dp)).background(ExpertColors.panel)
        ) {
            Box(Modifier.fillMaxSize().padding(end = 12.dp, bottom = 12.dp).horizontalScroll(horizontal).verticalScroll(vertical)) {
                val center = centerAnchor ?: ((viewport.width / density.density / zoom - width - 12f / zoom) / 2f).coerceAtLeast(0f)
                val displayOrigin = origin + Offset(center, 0f)
                Box(Modifier.width(maxOf(width * zoom, viewport.width / density.density - 12f).dp).height((height * zoom).dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        fun px(point: Offset): Offset = Offset(
                            (point.x + displayOrigin.x) * zoom * density.density,
                            (point.y + displayOrigin.y) * zoom * density.density
                        )
                        fun line(from: Offset, to: Offset, lane: Int = 0) {
                            val start = px(from + Offset(105f, 150f))
                            val end = px(to + Offset(105f, 0f))
                            val middle = if (end.y > start.y + 20.dp.toPx()) {
                                (start.y + end.y) / 2f + lane * 3.dp.toPx()
                            } else {
                                maxOf(start.y, end.y) + 18.dp.toPx() + lane * 8.dp.toPx()
                            }
                            val path = Path().apply {
                                moveTo(start.x, start.y)
                                lineTo(start.x, middle)
                                lineTo(
                                    end.x,
                                    middle
                                )
                                lineTo(end.x, end.y)
                            }
                            drawPath(path, ExpertColors.border, style = Stroke(1.5.dp.toPx()))
                        }
                        nodes.forEach { node ->
                            val from = node.parentId?.let { pointMap[it] } ?: root
                            pointMap[node.id]?.let { line(from, it) }
                        }
                        nodes.forEachIndexed { index, node ->
                            val target = node.target as? PolicyTarget.Channel
                            val destination = target?.id?.let { channelPoints[it] }
                            if (destination != null) pointMap[node.id]?.let { line(it, destination, index % 6) }
                        }
                        (tree.defaultTarget as? PolicyTarget.Channel)?.id?.let { id ->
                            channelPoints[id]?.let { destination ->
                                val start = px(root + Offset(105f, 150f))
                                val end = px(destination + Offset(105f, 0f))
                                val outsideX = (width + center - 20f) * zoom * density.density
                                val path = Path().apply {
                                    moveTo(start.x, start.y)
                                    lineTo(start.x, start.y + 16.dp.toPx())
                                    lineTo(outsideX, start.y + 16.dp.toPx())
                                    lineTo(outsideX, end.y - 16.dp.toPx())
                                    lineTo(end.x, end.y - 16.dp.toPx())
                                    lineTo(end.x, end.y)
                                }
                                drawPath(path, ExpertColors.border, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                        channels.forEachIndexed { index, channel ->
                            val linked = (channel.target as? PolicyTarget.Channel)?.id?.let { channelPoints[it] }
                            if (linked != null) line(channelPoints.getValue(channel.id), linked, index)
                        }
                    }
                    GraphCard(
                        root + displayOrigin, zoom, "Всё в этой схеме",
                        ExpertPolicyEditing.targetName(tree.defaultTarget, state), false, false, null,
                        defaultEdit, defaultEdit, { drag(PolicyCanvasKeys.ROOT, root, it) }, onDragEnd = ::commitDrag,
                        onDragStart = {
                            draggingKey = PolicyCanvasKeys.ROOT
                            centerAnchor = center
                        }, onDragCancel = ::cancelDrag
                    )
                    nodes.forEach { node ->
                        val point = pointMap.getValue(node.id)
                        GraphCard(
                            point + displayOrigin, zoom, node.title.ifBlank { "Без названия" },
                            ExpertPolicyEditing.conditionSummary(node),
                            selectedId == node.id, node.target is PolicyTarget.Channel,
                            ExpertPolicyEditing.inactiveReason(node, tree, state)
                                ?: if (!node.enabled) {
                                    "Выключена"
                                } else if (node.detached) {
                                    "Отсоединена"
                                } else {
                                    null
                                },
                            { select(node) }, { edit(node) }, { drag(PolicyCanvasKeys.node(node.id), point, it) },
                            target = ExpertPolicyEditing.targetName(node.target, state),
                            addChild = if (node.target !is PolicyTarget.Profile &&
                                node.target !is PolicyTarget.Folder &&
                                node.target !is PolicyTarget.Channel
                            ) {
                                ({ addChild(node) })
                            } else {
                                null
                            },
                            onDragEnd = ::commitDrag,
                            onDragStart = {
                                draggingKey = PolicyCanvasKeys.node(node.id)
                                centerAnchor = center
                            }, onDragCancel = ::cancelDrag
                        )
                    }
                    channels.forEach { channel ->
                        val point = channelPoints.getValue(channel.id)
                        GraphCard(
                            point + displayOrigin, zoom, "Канал: ${channel.name}",
                            "Входов: ${ExpertPolicyEditing.channelReferenceCount(state.draft, channel.id)}", false, true, null,
                            { channelEdit(channel) }, { channelEdit(channel) }, { drag(PolicyCanvasKeys.channel(channel.id), point, it) },
                            target = ExpertPolicyEditing.targetName(channel.target, state), onDragEnd = ::commitDrag,
                            onDragStart = {
                                draggingKey = PolicyCanvasKeys.channel(channel.id)
                                centerAnchor = center
                            },
                            onDragCancel = ::cancelDrag
                        )
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(vertical),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 12.dp)
            )
            HorizontalScrollbar(
                rememberScrollbarAdapter(horizontal),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = 12.dp)
            )
        }
    }
}

@Composable
private fun GraphCard(
    point: Offset,
    zoom: Float,
    title: String,
    description: String,
    selected: Boolean,
    channel: Boolean,
    inactive: String?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDrag: ((Offset) -> Unit)?,
    target: String = "Путь по умолчанию",
    addChild: (() -> Unit)? = null,
    onDragEnd: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val color = when {
        inactive != null -> ExpertColors.muted
        channel -> ExpertColors.amber
        selected -> ExpertColors.blue
        else -> ExpertColors.border
    }
    val density = LocalDensity.current
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDragCancel by rememberUpdatedState(onDragCancel)
    val dragModifier = if (onDrag == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDrag = { change, delta ->
                    change.consume()
                    currentDrag?.invoke(delta)
                },
                onDragStart = { currentDragStart() }, onDragEnd = { currentDragEnd() }, onDragCancel = { currentDragCancel() }
            )
        }
    }
    Card(
        Modifier.offset { IntOffset((point.x * zoom * density.density).roundToInt(), (point.y * zoom * density.density).roundToInt()) }
            .width((210f * zoom).dp).height((150f * zoom).dp).then(dragModifier).clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = "$title. $description. $target.${inactive?.let { " Неактивно: $it" }.orEmpty()}"
            },
        colors = CardDefaults.cardColors(containerColor = if (channel) ExpertColors.amber.copy(alpha = .08f) else ExpertColors.background),
        border = BorderStroke(if (selected) 2.dp else 1.dp, color), shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding((11f * zoom).dp), verticalArrangement = Arrangement.spacedBy((5f * zoom).dp)) {
            Text(
                title, color = if (inactive != null) ExpertColors.muted else ExpertColors.text,
                fontWeight = FontWeight.SemiBold, fontSize = (14f * zoom).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                inactive ?: description, color = ExpertColors.muted, fontSize = (11f * zoom).sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                target, color = if (channel) ExpertColors.amber else ExpertColors.blue,
                fontSize = (11f * zoom).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onEdit, modifier = Modifier.height((36f * zoom).dp)) { Text("Свойства", fontSize = (11f * zoom).sp) }
                addChild?.let {
                    TextButton(onClick = it, modifier = Modifier.height((36f * zoom).dp)) {
                        Text(
                            "+",
                            fontSize = (15f * zoom).sp
                        )
                    }
                }
            }
        }
    }
}
