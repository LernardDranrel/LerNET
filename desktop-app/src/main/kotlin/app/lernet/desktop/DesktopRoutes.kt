package app.lernet.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.desktop.expert.*
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionCodec
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.PatternSign
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RouteLayoutNode
import app.lernet.routing.RouteTreeLayout
import app.lernet.routing.RuleConditions
import app.lernet.routing.RuleMatch
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyTree
import app.lernet.routing.policy.vpnOwnerScope
import app.lernet.routing.policy.vpnRuleBranch
import app.lernet.ui.routes.CountryCatalog
import app.lernet.ui.routes.CountryNames
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val routeBackground = Color(0xFF101724)
private val routeBorder = Color(0xFF3B4961)
private val routeText = Color(0xFFF5F7FB)
private val routeMuted = Color(0xFFA1AEC4)
private val routeBlue = Color(0xFF91ABFF)
private val routeGreen = Color(0xFF80DEBE)
private val routeRed = Color(0xFFFFB4AB)
private val routeAmber = Color(0xFFF5C16C)
private val routeCard = Color(0xFF151D2B)


@Composable
fun DesktopRoutes(saved: StoredState, controller: DesktopController, preferredOwnerId: String? = null) {
    val profile = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
    val owners = saved.groups.map { "grp_${it.id}" to "Папка: ${it.name}" } +
        saved.profiles.map { it.id to "Конфиг: ${it.name}" }
    var ownerId by rememberSaveable(preferredOwnerId, saved.selectedProfileId) {
        mutableStateOf(preferredOwnerId?.takeIf { id -> owners.any { it.first == id } }
            ?: profile?.groupId?.let { "grp_$it" } ?: profile?.id ?: owners.firstOrNull()?.first)
    }
    val activeOwner = ownerId?.takeIf { id -> owners.any { it.first == id } }
        ?: owners.firstOrNull()?.first
    if (activeOwner == null) {
        RouteEmpty("Сначала выберите конфиг", "Правила принадлежат конкретному конфигу и применяются при следующем подключении.")
        return
    }
    val ownerProfile = saved.profiles.firstOrNull { it.id == activeOwner }
    val ownerGroup = ownerProfile?.groupId?.let { id -> saved.groups.firstOrNull { it.id == id } }
    var draft by remember(activeOwner) { mutableStateOf(controller.routeDraft(activeOwner)) }
    val rules = draft.rules
    val dirty = draft.dirty
    fun keep(next: DesktopController.RuleDraft) {
        draft = next
        controller.keepRuleDraft(activeOwner, next)
    }
    LaunchedEffect(activeOwner, saved) {
        if (!draft.dirty) draft = controller.routeDraft(activeOwner)
    }
    val byId = remember(rules) { rules.associateBy { it.id } }
    val nodes = remember(rules) { rules.map { it.asBranch() } }
    val tree = remember(activeOwner, nodes, draft.layout) { PolicyTree(vpnOwnerScope(activeOwner), nodes, positions = draft.layout) }
    val branches = remember(nodes) { DesktopRuleBranches(tree) }
    val inactive = remember(rules) { DesktopRouteTree.platformInactiveIds(rules) }
    var selectedId by rememberSaveable(activeOwner) { mutableStateOf(branches.children[null]?.firstOrNull()?.id) }
    var creatingOtherwise by rememberSaveable(activeOwner) { mutableStateOf(false) }
    var selectedChannel by rememberSaveable(activeOwner) { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(activeOwner, stateSaver = routeEditorStateSaver<StoredRule?>()) { mutableStateOf<StoredRule?>(null) }
    var deleting by remember(activeOwner) { mutableStateOf<StoredRule?>(null) }
    var ownerMenu by remember { mutableStateOf(false) }
    var pendingOwner by remember { mutableStateOf<String?>(null) }
    var draftError by remember(activeOwner) { mutableStateOf<String?>(null) }
    fun applyEdit(edit: DesktopRouteTree.Edit): Boolean {
        if (edit.error != null) {
            draftError = edit.error
            return false
        }
        val removed = (rules.map { it.id } - edit.rules.mapTo(mutableSetOf()) { it.id }).map(PolicyCanvasKeys::node)
        keep(draft.copy(rules = edit.rules, layout = draft.layout - removed.toSet()))
        draftError = null
        return true
    }
    fun saveDraft(): Boolean {
        if (!dirty) return true
        if (!controller.commitRuleDraft(activeOwner, draft)) {
            draftError = "Не удалось сохранить черновик. Проверьте сообщение о состоянии приложения."
            return false
        }
        controller.clearRuleDraft(activeOwner)
        draft = controller.routeDraft(activeOwner)
        draftError = null
        return true
    }
    fun chooseOwner(id: String) {
        ownerMenu = false
        if (id != activeOwner) {
            if (dirty) pendingOwner = id else ownerId = id
        }
    }
    val selected = rules.firstOrNull { it.id == selectedId }
    LaunchedEffect(rules, selectedChannel) {
        if (selectedChannel != null && selectedChannel !in namedChannelSources(rules, activeOwner)) {
            selectedChannel = null
        }
    }
    val previewProfileId = if (activeOwner.startsWith("grp_"))
        saved.profiles.firstOrNull { it.groupId == activeOwner.removePrefix("grp_") }?.id else activeOwner
    val preview = remember(
        rules, previewProfileId, saved.mode, saved.defaultDnsPolicy, saved.tunMtu, saved.xmuxChannels, saved.directDnsServer,
    ) {
        previewProfileId?.let { runCatching { controller.preview(it, draftRules = rules, draftOwnerId = activeOwner) }.getOrNull() }
    }

    fun add(parent: String?) {
        creatingOtherwise = false
        editing = newRule(activeOwner, parent, rules)
    }
    fun editRoot() {
        val existing = rules.firstOrNull { it.parentId == null && DesktopRouteTree.isElse(it) }
        creatingOtherwise = existing == null
        editing = existing ?: newRule(activeOwner, null, rules).copy(title = "Иначе")
    }
    fun inactiveReason(node: app.lernet.routing.policy.PolicyNode): String? {
        if (node.id in inactive) return "Неактивно в Windows"
        val ancestor = branches.path(node.id).firstOrNull { it.detached || !it.enabled } ?: return null
        return when {
            ancestor.detached -> "Ветка отсоединена"
            ancestor.id == node.id -> "Ветка выключена"
            else -> "Родительская ветка выключена"
        }
    }
    val ownerPicker: @Composable () -> Unit = {
        Box(Modifier.widthIn(max = 170.dp)) {
            MapButton((owners.firstOrNull { it.first == activeOwner }?.second ?: activeOwner) + " ▾", { ownerMenu = true })
            DropdownMenu(ownerMenu, onDismissRequest = { ownerMenu = false }) {
                saved.groups.forEach { group ->
                    DropdownMenuItem(text = { Text("▣  ${group.name}", fontWeight = FontWeight.SemiBold) },
                        onClick = { chooseOwner("grp_${group.id}") })
                    saved.profiles.filter { it.groupId == group.id }.forEach { child ->
                        DropdownMenuItem(text = {
                            Column(Modifier.padding(start = 22.dp)) {
                                Text("└  ${child.name}")
                                Text("Действуют правила папки", color = routeMuted, fontSize = 12.sp)
                            }
                        }, onClick = { chooseOwner(child.id) })
                    }
                }
                if (saved.groups.isNotEmpty() && saved.profiles.any { it.groupId == null })
                    HorizontalDivider(color = routeBorder)
                saved.profiles.filter { it.groupId == null }.forEach { ungrouped ->
                    DropdownMenuItem(text = { Text("▢  ${ungrouped.name}") },
                        onClick = { chooseOwner(ungrouped.id) })
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (dirty) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Есть несохранённые изменения", Modifier.weight(1f), color = routeMuted, fontSize = 13.sp)
                MapButton("Отменить", {
                    controller.clearRuleDraft(activeOwner)
                    draft = controller.routeDraft(activeOwner)
                    selectedId = null
                    selectedChannel = null
                    draftError = null
                })
                MapButton("Сохранить", { saveDraft() }, primary = true)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (draftError != null) {
            Text(draftError.orEmpty(), color = routeRed, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (ownerGroup != null) {
            Surface(color = Color(0xFF302B1D), shape = RoundedCornerShape(10.dp)) {
                Text("Конфиг находится в папке «${ownerGroup.name}». Сейчас для него действуют правила папки. Эти правила конфига сохранятся, но начнут работать только после переноса конфига из папки.",
                    color = Color(0xFFF5C16C), modifier = Modifier.padding(12.dp), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (preview != null && !preview.isValid) {
            val error = preview.errors.firstOrNull() ?: "Не удалось проверить правила"
            Surface(color = Color(0xFF402B30), shape = RoundedCornerShape(10.dp)) {
                Text(error, color = routeRed, modifier = Modifier.padding(10.dp), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        DesktopBranchWorkspace(
            tree, branches, selectedId, { selectedId = it?.id; selectedChannel = null },
            targetName = { actionLabelDesktop(byId.getValue(it.id)) },
            inactiveReason = ::inactiveReason,
            canAdd = { true }, add = { add(it?.id) },
            move = { key, point -> keep(draft.copy(layout = draft.layout + (key to point))) },
            arrange = { keep(draft.copy(layout = emptyMap())) }, modifier = Modifier.fillMaxSize(),
            overlayOpen = editing != null || deleting != null || pendingOwner != null,
            deleteSelected = selected?.let { { deleting = it } },
            scopePicker = ownerPicker,
            reorder = { node, delta -> applyEdit(DesktopRouteTree.move(rules, node.id, delta)) },
            listExtras = { select ->
                namedChannelSources(rules, activeOwner).forEach { (channel, sources) ->
                    item(key = PolicyCanvasKeys.channel(channel)) {
                        BranchListChannel(channel, sources.joinToString { displayTitle(it) }, actionLabelDesktop("PROXY")) {
                            select(null)
                            selectedChannel = channel
                        }
                    }
                }
            },
            fullView = { select ->
                RouteScheme(activeOwner, rules, selectedId, selectedChannel, selectedId == null && selectedChannel == null,
                    onSelect = { select(branches.byId[it]) }, onSelectChannel = { select(null); selectedChannel = it },
                    onAddRoot = { add(null) }, onAddChild = { add(it.id) }, onReorder = { id, target, after ->
                        applyEdit(DesktopRouteTree.reorder(rules, id, target, after))
                    }, pins = draft.layout)
            },
            inspector = { panel, close, focus, go ->
                val channel = selectedChannel
                val node = branches.byId[selectedId]
                if (channel != null) {
                    RuleInspector(channel, "Выбранный канал", panel, close) {
                        val sources = namedChannelSources(rules, activeOwner)[channel].orEmpty()
                        InspectorValue("Источников", sources.size.toString())
                        sources.forEach { source ->
                            MapButton(displayTitle(source), { selectedChannel = null; selectedId = source.id })
                        }
                    }
                } else {
                    val otherwise = rules.firstOrNull { it.parentId == null && DesktopRouteTree.isElse(it) }
                    val status = when {
                        selected == null -> null
                        selected.id in inactive -> DesktopRouteTree.platformNote(selected, rules)
                        else -> node?.let(::inactiveReason)
                    }
                    BranchRuleInspector(node, branches, panel, close, focus, go, status,
                        destination = selected?.let(::actionLabelDesktop) ?: otherwise?.let(::actionLabelDesktop) ?: actionLabelDesktop("PROXY"),
                        onEdit = { creatingOtherwise = false; if (selected == null) editRoot() else editing = selected },
                        onAdd = { add(selected?.id) },
                    ) {
                        if (selected != null) {
                            var more by remember(selected.id) { mutableStateOf(false) }
                            Box {
                                MapButton("Действия ▾", { more = true })
                                DropdownMenu(more, { more = false }) {
                                    val peers = DesktopRouteTree.siblings(rules, activeOwner, selected.parentId).filterNot(DesktopRouteTree::isElse)
                                    val index = peers.indexOfFirst { it.id == selected.id }
                                    listOf(-1 to "Выше", 1 to "Ниже").forEach { (delta, label) ->
                                        DropdownMenuItem(text = { Text(label) }, enabled = index >= 0 && index + delta in peers.indices,
                                            onClick = { more = false; applyEdit(DesktopRouteTree.move(rules, selected.id, delta)) })
                                    }
                                    DropdownMenuItem(text = { Text(if (selected.enabled) "Выключить ветку" else "Включить ветку") },
                                        enabled = selected.id !in inactive, onClick = {
                                            more = false; applyEdit(DesktopRouteTree.save(rules, selected.copy(enabled = !selected.enabled)))
                                        })
                                    DropdownMenuItem(text = { Text("Удалить", color = routeRed) },
                                        onClick = { more = false; deleting = selected; close?.invoke() })
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    editing?.let { draft ->
        RouteRuleEditor(
            draft = draft,
            rules = rules,
            newOtherwise = creatingOtherwise,
            onClose = { editing = null },
            onSave = { if (applyEdit(DesktopRouteTree.save(rules, it))) { selectedId = it.id; selectedChannel = null; creatingOtherwise = false; editing = null } },
        )
    }
    deleting?.let { rule ->
        val descendants = descendants(rules, rule.id)
        AlertDialog(
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        if (applyEdit(DesktopRouteTree.delete(rules, rule.id))) { selectedId = null; deleting = null }
                        true
                    }
                    Key.Escape -> { deleting = null; true }
                    else -> false
                }
            },
            onDismissRequest = { deleting = null },
            title = { Text("Удалить правило?") },
            text = { Text(if (descendants.isEmpty()) "«${displayTitle(rule)}» будет удалено." else "«${displayTitle(rule)}» и ${descendants.size} дочерних правил будут удалены.") },
            confirmButton = { TextButton(onClick = {
                if (applyEdit(DesktopRouteTree.delete(rules, rule.id))) { selectedId = null; deleting = null }
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
    pendingOwner?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingOwner = null },
            title = { Text("Несохранённый черновик") },
            text = { Text("Сохранить изменения правил перед переходом?") },
            confirmButton = { Button(onClick = { if (saveDraft()) { pendingOwner = null; ownerId = target } }) { Text("Сохранить и перейти") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingOwner = null }) { Text("Остаться") }
                    TextButton(onClick = { controller.clearRuleDraft(activeOwner); pendingOwner = null; ownerId = target }) { Text("Отменить изменения") }
                }
            },
        )
    }
}

private fun StoredRule.shownConditions(): RuleConditions {
    val decoded = ConditionCodec.decode(blocksJson, RuleMatch(apps, domains, domainSuffixes, cidrs, countries, processes))
    return if (blocksJson.isBlank()) decoded.copy(join = MatchJoin.entries.firstOrNull { it.name.equals(join, true) } ?: decoded.join)
        else decoded
}

internal fun StoredRule.asBranch() = vpnRuleBranch(id, parentId, sortIndex, displayTitle(this), enabled, shownConditions(),
    DesktopRouteTree.isElse(this), action, pipeName)

private fun newRule(profileId: String, parentId: String?, rules: List<StoredRule>): StoredRule = StoredRule(
    id = UUID.randomUUID().toString(), profileId = profileId, parentId = parentId,
    sortIndex = DesktopRouteTree.siblings(rules, profileId, parentId).count { !DesktopRouteTree.isElse(it) },
)

private fun descendants(rules: List<StoredRule>, id: String): Set<String> {
    val found = mutableSetOf<String>()
    do {
        val before = found.size
        rules.filter { it.parentId == id || it.parentId in found }.forEach { found += it.id }
    } while (found.size != before)
    return found
}

private fun orderedTree(rules: List<StoredRule>, profileId: String): List<Pair<StoredRule, Int>> {
    val out = mutableListOf<Pair<StoredRule, Int>>()
    val visited = mutableSetOf<String>()
    fun walk(parentId: String?, depth: Int) {
        DesktopRouteTree.siblings(rules, profileId, parentId).forEach { rule ->
            if (visited.add(rule.id)) {
                out += rule to depth
                walk(rule.id, depth + 1)
            }
        }
    }
    walk(null, 0)
    return out
}

internal fun namedChannelSources(rules: List<StoredRule>, profileId: String): Map<String, List<StoredRule>> {
    val attached = orderedTree(rules, profileId).map { it.first }
    val parents = attached.mapNotNull { it.parentId }.toSet()
    return attached.filter { it.action == "PROXY" && it.pipeName.isNotBlank() && it.id !in parents }
        .groupBy { it.pipeName }
}

@Composable
private fun ChannelPortalMark(count: Int) {
    Canvas(Modifier.size(18.dp).semantics { contentDescription = "Общий канал, источников: $count" }) {
        val stroke = 1.7.dp.toPx()
        val middle = size.height / 2f
        val left = size.width * .08f
        val join = size.width * .47f
        val ring = Offset(size.width * .73f, middle)
        drawLine(routeAmber, Offset(left, size.height * .2f), Offset(join, middle), stroke, StrokeCap.Round)
        drawLine(routeAmber, Offset(left, size.height * .8f), Offset(join, middle), stroke, StrokeCap.Round)
        drawLine(routeAmber, Offset(join, middle), Offset(size.width * .57f, middle), stroke, StrokeCap.Round)
        drawCircle(routeAmber, radius = size.width * .21f, center = ring, style = Stroke(stroke))
    }
}

@Composable
private fun RouteScheme(
    profileId: String,
    rules: List<StoredRule>,
    selectedId: String?,
    selectedChannel: String?,
    rootSelected: Boolean,
    onSelect: (String?) -> Unit,
    onSelectChannel: (String) -> Unit,
    onAddRoot: () -> Unit,
    onAddChild: (StoredRule) -> Unit,
    onReorder: (String, String, Boolean) -> Unit,
    pins: Map<String, PolicyCanvasPoint>,
) {
    val nodeBounds = remember(profileId) { mutableStateMapOf<String, Rect>() }
    var draggingId by remember(profileId) { mutableStateOf<String?>(null) }
    var dragStart by remember(profileId) { mutableStateOf(Offset.Zero) }
    var dragDelta by remember(profileId) { mutableStateOf(Offset.Zero) }
    val sourceRule = rules.firstOrNull { it.id == draggingId }
    val dropPoint = if (draggingId != null) dragStart + dragDelta else null
    val hoverTarget = dropPoint?.let { point -> nodeBounds.entries.firstOrNull { entry ->
        val candidate = rules.firstOrNull { it.id == entry.key }
        candidate != null && candidate.id != draggingId && candidate.parentId == sourceRule?.parentId &&
            !DesktopRouteTree.isElse(candidate) && entry.value.contains(point)
    }?.key }
    val layout = remember(rules, profileId) {
        RouteTreeLayout.vertical(orderedTree(rules, profileId).map { (rule, _) ->
            RouteLayoutNode(rule.id, rule.parentId)
        }, column = 274f, row = 146f)
    }
    val root = pins[PolicyCanvasKeys.ROOT]?.let { RulePosition(it.x, it.y) } ?: RulePosition(layout.root.x, layout.root.y)
    val positions = layout.nodes.mapValues { (id, point) ->
        pins[PolicyCanvasKeys.node(id)]?.let { RulePosition(it.x, it.y) } ?: RulePosition(point.x, point.y)
    }
    val pipeSources = namedChannelSources(rules, profileId)
    var previousPipeX = Float.NEGATIVE_INFINITY
    val pipeY = (positions.values.maxOfOrNull { it.y } ?: root.y) + 146f
    val pipePositions = pipeSources.map { (name, sources) ->
        val preferred = sources.mapNotNull { positions[it.id]?.x?.plus(15f) }.average().toFloat()
        name to preferred
    }.sortedBy { it.second }.associate { (name, preferred) ->
        val x = preferred.coerceAtLeast(previousPipeX + 210f)
        previousPipeX = x
        name to RulePosition(x, pipeY)
    }
    val points = positions.values + pipePositions.values + root
    val bounds = Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x } + 254f, points.maxOf { it.y } + 134f)
    RouteEditorCanvas(profileId, bounds,
        Modifier.fillMaxSize(),
        connections = { transform ->
            fun anchor(p: RulePosition, outgoing: Boolean) = Offset(
                (p.x + 105f).dp.toPx() * transform.zoom + transform.pan.x,
                (p.y + if (outgoing) 76f else 0f).dp.toPx() * transform.zoom + transform.pan.y,
            )
            rules.forEach { rule ->
                val from = rule.parentId?.let(positions::get) ?: root
                val to = positions[rule.id] ?: return@forEach
                val start = anchor(from, true)
                val end = anchor(to, false)
                val bend = (start.y + end.y) / 2f
                val path = Path().apply { moveTo(start.x, start.y); lineTo(start.x, bend); lineTo(end.x, bend); lineTo(end.x, end.y) }
                drawPath(path, if (rule.id == selectedId) routeBlue else routeBorder, style = Stroke(width = if (rule.id == selectedId) 2.5.dp.toPx() else 1.5.dp.toPx()))
                drawCircle(if (rule.id == selectedId) routeBlue else routeBorder, radius = 3.dp.toPx(), center = end)
            }
            pipeSources.forEach { (name, sources) ->
                val target = pipePositions[name] ?: return@forEach
                sources.sortedBy { positions[it.id]?.x ?: 0f }.forEachIndexed { index, source ->
                    val origin = positions[source.id] ?: return@forEachIndexed
                    val start = anchor(origin, true)
                    val inletX = target.x + 180f * (index + 1) / (sources.size + 1)
                    val end = Offset(inletX.dp.toPx() * transform.zoom + transform.pan.x,
                        target.y.dp.toPx() * transform.zoom + transform.pan.y)
                    val middleY = (start.y + end.y) / 2f
                    val path = Path().apply {
                        moveTo(start.x, start.y)
                        lineTo(start.x, middleY)
                        lineTo(end.x, middleY)
                        lineTo(end.x, end.y)
                    }
                    val highlighted = selectedChannel == name
                    drawPath(path, if (highlighted) routeAmber else routeBorder,
                        style = Stroke(width = (if (highlighted) 2.5.dp else 1.5.dp).toPx()))
                    drawCircle(if (highlighted) routeAmber else routeBorder,
                        radius = (if (highlighted) 3.dp else 2.dp).toPx(), center = end)
                }
            }
        }, content = { transform ->
        @Composable fun positioned(position: RulePosition, z: Float = 0f, content: @Composable () -> Unit) {
            RouteEditorPositioned(Offset(position.x, position.y), transform, z, content)
        }
        positioned(root) {
            RouteEditorNodeActions(rootSelected, onAddRoot) {
                RouteEditorNodeFace(app.lernet.engine.text.SchemaWords.ROOT_TITLE, app.lernet.engine.text.SchemaWords.ROOT_SUBTITLE, app.lernet.engine.text.SchemaWords.VPN_ROOT_TARGET, routeBlue,
                    rootSelected, Modifier.clickable { onSelect(null) })
            }
        }
        rules.forEach { rule ->
            val position = positions[rule.id] ?: return@forEach
            positioned(position, if (draggingId == rule.id) 10f else 0f) {
                RouteNodeCard(
                    rule = rule, selected = rule.id == selectedId,
                    unavailable = rule.id in DesktopRouteTree.platformInactiveIds(rules),
                    childCount = rules.count { it.parentId == rule.id },
                    dragging = draggingId == rule.id, dropTarget = hoverTarget == rule.id, dragOffset = dragDelta / transform.zoom,
                    zoom = transform.zoom,
                    onBounds = { nodeBounds[rule.id] = it },
                    onClick = { onSelect(rule.id) },
                    onAddChild = { onAddChild(rule) },
                    onDragStart = { local ->
                        draggingId = rule.id
                        dragStart = (nodeBounds[rule.id]?.topLeft ?: Offset.Zero) + local
                        dragDelta = Offset.Zero
                    },
                    onDrag = { dragDelta += it },
                    onDragEnd = {
                        val targetId = hoverTarget
                        if (targetId != null) onReorder(rule.id, targetId, dropPoint?.y?.let { y -> y > (nodeBounds[targetId]?.center?.y ?: y) } == true)
                        draggingId = null; dragDelta = Offset.Zero
                    },
                    onDragCancel = { draggingId = null; dragDelta = Offset.Zero },
                )
            }
        }
        pipePositions.forEach { (name, position) ->
            positioned(position) {
                Surface(shape = RoundedCornerShape(10.dp), color = routeCard,
                    border = androidx.compose.foundation.BorderStroke(if (selectedChannel == name) 2.dp else 1.dp, routeAmber),
                    modifier = Modifier.width(180.dp).height(56.dp).clickable { onSelectChannel(name) }) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = routeAmber, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                            val count = pipeSources[name].orEmpty().size
                            if (count > 1) ChannelPortalMark(count)
                        }
                        Text("Источников: ${pipeSources[name].orEmpty().size}", color = routeMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        if (rules.isEmpty()) {
            Text("Добавьте первое правило. Схема и список используют одни данные.", color = routeMuted,
                modifier = Modifier.align(Alignment.BottomStart).padding(20.dp))
        }
    })
}

@Composable
private fun RouteNodeCard(rule: StoredRule, selected: Boolean, unavailable: Boolean, childCount: Int,
    dragging: Boolean, dropTarget: Boolean, dragOffset: Offset, zoom: Float, onBounds: (Rect) -> Unit,
    onClick: () -> Unit, onAddChild: () -> Unit, onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit, onDragCancel: () -> Unit) {
    val tone = when {
        unavailable || !rule.enabled -> routeMuted
        childCount > 0 -> routeBlue
        rule.action == "DIRECT" -> routeGreen
        rule.action == "BLOCK" -> routeRed
        rule.pipeName.isNotBlank() -> routeAmber
        else -> routeBlue
    }
    RouteEditorNodeActions(selected && !dragging, if (!dragging) onAddChild else null) {
        RouteNodeFace(rule, childCount, tone, selected, dropTarget,
            Modifier.graphicsLayer { alpha = if (dragging) .35f else 1f }
                .onGloballyPositioned { onBounds(it.routeEditorBounds()) }
                .routeEditorDrag(rule.id, zoom, onDragStart, onDrag, onDragEnd, onDragCancel).clickable(onClick = onClick), unavailable = unavailable)
        if (dragging) RouteNodeFace(rule, childCount, tone, selected = true, dropTarget = false,
            modifier = Modifier.graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                shadowElevation = 22f
            }, unavailable = unavailable)

    }
}

@Composable
private fun RouteNodeFace(rule: StoredRule, childCount: Int, tone: Color, selected: Boolean,
    dropTarget: Boolean, modifier: Modifier = Modifier, unavailable: Boolean = false) {
    RouteEditorNodeFace(
        title = displayTitle(rule),
        description = if (DesktopRouteTree.isElse(rule)) "Любой оставшийся трафик" else ruleSummary(rule),
        target = if (unavailable) "Только Android · неактивно" else if (childCount > 0) "Развилка · $childCount ветвей"
            else actionLabelDesktop(rule),
        tone = tone, selected = selected, modifier = modifier, muted = unavailable || !rule.enabled,
        highlighted = dropTarget, channel = false, ordinal = "${rule.sortIndex + 1}",
    )
}

@Composable
private fun RouteRuleEditor(draft: StoredRule, rules: List<StoredRule>, newOtherwise: Boolean, onClose: () -> Unit, onSave: (StoredRule) -> Unit) {
    val wasElse = newOtherwise || rules.firstOrNull { it.id == draft.id }?.let(DesktopRouteTree::isElse) == true
    var title by rememberSaveable(draft.id) { mutableStateOf(draft.title) }
    var parentId by rememberSaveable(draft.id) { mutableStateOf(draft.parentId) }
    var priority by rememberSaveable(draft.id) { mutableIntStateOf(draft.sortIndex) }
    var conditions by rememberSaveable(draft.id, stateSaver = routeEditorStateSaver<RuleConditions>()) { mutableStateOf(
        draft.shownConditions()
    ) }
    var action by rememberSaveable(draft.id) { mutableStateOf(draft.action.uppercase()) }
    var namedPipe by rememberSaveable(draft.id) { mutableStateOf(draft.pipeName.isNotBlank()) }
    val hadPipe = remember(draft.id) { draft.pipeName.isNotBlank() }
    var pipeName by rememberSaveable(draft.id) { mutableStateOf(draft.pipeName) }
    val currentRule = draft.copy(parentId = parentId, blocksJson = ConditionCodec.encode(conditions))
    val platformNote = DesktopRouteTree.platformNote(currentRule, rules.filterNot { it.id == draft.id } + currentRule)
    val candidates = rules.filter { it.id != draft.id && it.id !in descendants(rules, draft.id) }
    val hasChildren = rules.any { it.parentId == draft.id }
    val maxPriority = rules.count { it.profileId == draft.profileId && it.parentId == parentId && it.id != draft.id && !DesktopRouteTree.isElse(it) }
    val conditionErrors = if (wasElse) emptyList() else RouteCompiler.validateConditions(draft.id, conditions).map { it.message }
    val valid = conditionErrors.isEmpty() && (wasElse || (conditions.blocks.isNotEmpty() && conditions.blocks.all { block -> block.values.any(String::isNotBlank) })) &&
        (hasChildren || action != "PROXY" || !namedPipe || pipeName.trim().isNotBlank())
    fun save() {
        val next = draft.copy(title = title.trim(), parentId = parentId, sortIndex = priority.coerceIn(0, maxPriority),
            action = action, pipeName = if (!hasChildren && action == "PROXY" && namedPipe) pipeName.trim() else "")
        if (wasElse || conditions == draft.shownConditions()) {
            onSave(next)
        } else {
            val flat = ConditionCodec.project(conditions)
            onSave(next.copy(join = conditions.join.name, domains = flat.domains, domainSuffixes = flat.domainSuffixes,
                cidrs = flat.ipCidrs, countries = flat.geoip, processes = flat.processes, apps = flat.apps,
                blocksJson = ConditionCodec.encode(conditions)))
        }
    }
    RouteEditorModal(if (wasElse) "Ветка ИНАЧЕ" else "Правило", onClose, ::save, valid, "В черновик") {
        OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (platformNote != null) Text(platformNote, color = routeMuted, fontSize = 12.sp)
        if (!wasElse) ExpertSelect("Родитель", listOf(null to "Корень схемы") + candidates.map { it.id to displayTitle(it) }, parentId) { parent ->
            parentId = parent
            priority = rules.count { it.profileId == draft.profileId && it.parentId == parent && it.id != draft.id && !DesktopRouteTree.isElse(it) }
        }
        Text(ruleBreadcrumb(parentId, rules), color = routeMuted, fontSize = 12.sp)
        if (!wasElse) {
            Text("Приоритет ${priority.coerceIn(0, maxPriority) + 1} · порядок можно изменить в действиях правила или перетаскиванием в списке",
                color = routeMuted, fontSize = 12.sp)
        }
        if (!wasElse) {
            RouteConditionsEditor(conditions, { conditions = it }, ConditionKind.entries.filterNot { it == ConditionKind.APP })
            if (!valid) Text(conditionErrors.joinToString("\n").ifBlank { "Добавьте хотя бы одно значение в каждый блок условия." }, color = routeRed, fontSize = 12.sp)
        } else Text("Правило срабатывает, когда ни одно предыдущее правило этого уровня не подошло.", color = routeMuted)
        if (hasChildren) {
            Text("ВЛОЖЕННЫЕ ПРАВИЛА", color = routeBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("Этот блок проверяет условие. Действие задают его дочерние правила, включая обязательное «Иначе».", color = routeMuted, fontSize = 12.sp)
        } else {
            Text("ДЕЙСТВИЕ", color = routeMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                basicDesktopActions(draft.action.equals("BLOCK", ignoreCase = true)).forEach { value ->
                    FilterChip(action == value && (value != "PROXY" || !namedPipe),
                        onClick = { action = value; namedPipe = false }, label = { Text(actionLabelDesktop(value)) })
                }
                // Audit R2: a separate connection is an Expert tool; a rule that already has one keeps the choice.
                if (hadPipe) FilterChip(action == "PROXY" && namedPipe,
                    onClick = { action = "PROXY"; namedPipe = true }, label = { Text("Отдельное соединение") })
            }
            if (action == "BLOCK") {
                Text(BASIC_BLOCK_EXPERT_ONLY, color = routeAmber, fontSize = 12.sp)
            }
            if (action == "PROXY" && namedPipe) {
                OutlinedTextField(pipeName, { pipeName = it }, label = { Text("Название соединения") },
                    supportingText = { Text("Одинаковое имя — одно общее соединение. Каждое новое имя — ещё одно подключение к тому же серверу, оно заметно сети.") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                val existingPipes = rules.map { it.pipeName }.filter(String::isNotBlank).distinct()
                if (existingPipes.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        existingPipes.take(4).forEach { name ->
                            AssistChip(onClick = { pipeName = name }, label = { Text(name, maxLines = 1) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouteConditionsEditor(
    conditions: RuleConditions, onChange: (RuleConditions) -> Unit, kinds: List<ConditionKind>,
    onPickProcesses: ((Int) -> Unit)? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Между условиями", color = routeMuted)
        FilterChip(conditions.join == MatchJoin.AND, { onChange(conditions.copy(join = MatchJoin.AND)) }, label = { Text("И") })
        FilterChip(conditions.join == MatchJoin.OR, { onChange(conditions.copy(join = MatchJoin.OR)) }, label = { Text("ИЛИ") })
    }
    conditions.blocks.forEachIndexed { index, block ->
        key(index, block.kind) {
            RouteConditionBlockEditor(block, onChange = { next ->
                onChange(conditions.copy(blocks = conditions.blocks.toMutableList().also { it[index] = next }))
            }, onRemove = {
                onChange(conditions.copy(blocks = conditions.blocks.filterIndexed { i, _ -> i != index }))
            }, onPickProcesses = onPickProcesses?.let { pick -> { pick(index) } })
        }
    }
    ExpertSelect<ConditionKind?>("Добавить условие", listOf(null to "Выбери тип") + kinds.map { it to conditionKindTitle(it) }, null) { kind ->
        if (kind != null) onChange(conditions.copy(blocks = conditions.blocks + ConditionBlock(kind,
            if (kind == ConditionKind.PRIVATE) listOf("private") else emptyList())))
    }
}

private fun conditionKindTitle(kind: ConditionKind): String = when (kind) {
    ConditionKind.DOMAIN -> "Домены"
    ConditionKind.GEOIP -> "Страны"
    ConditionKind.PRIVATE -> "Частные сети"
    ConditionKind.CIDR -> "IP / CIDR"
    ConditionKind.PROCESS -> "Windows-процессы"
    ConditionKind.APP -> "Android-приложения"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RouteConditionBlockEditor(
    block: ConditionBlock,
    onChange: (ConditionBlock) -> Unit,
    onRemove: () -> Unit,
    onPickProcesses: (() -> Unit)? = null,
) {
    var input by rememberSaveable(block.kind) { mutableStateOf("") }
    var excludeNew by rememberSaveable(block.kind) { mutableStateOf(false) }
    var countryPicker by remember { mutableStateOf(false) }
    var processPicker by remember { mutableStateOf(false) }
    Surface(color = routeCard, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, routeBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conditionKindTitle(block.kind), color = routeText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Удалить блок", color = routeRed) }
            }
            if (block.values.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.values.forEachIndexed { index, value ->
                        val negative = PatternSign.negated(value)
                        Surface(color = if (negative) Color(0xFF42323A) else Color(0xFF253C37), shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (negative) routeRed.copy(alpha = .55f) else routeGreen.copy(alpha = .55f))) {
                            Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text((if (negative) "Кроме: " else "✓ ") + PatternSign.body(value), color = routeText, maxLines = 1,
                                    modifier = Modifier.clickable {
                                        onChange(block.copy(values = block.values.toMutableList().also { it[index] = PatternSign.signed(value, !negative) }))
                                    }.padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp))
                                Text("×", color = routeRed, modifier = Modifier.clickable {
                                    onChange(block.copy(values = block.values.filterIndexed { i, _ -> i != index }))
                                }.padding(horizontal = 10.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
                Text("Нажмите значение, чтобы переключить его на отрицательное условие. × удаляет значение.", color = routeMuted, fontSize = 12.sp)
            }
            when (block.kind) {
                ConditionKind.PRIVATE -> if (block.values.isEmpty()) TextButton(onClick = { onChange(block.copy(values = listOf("private"))) }) { Text("Добавить частные сети") }
                ConditionKind.GEOIP -> OutlinedButton(onClick = { countryPicker = true }) { Text("Выбрать страны ▾") }
                ConditionKind.APP -> Text("Условие для Android. В Windows эта ветка и её дочерние правила неактивны, но сохраняются для экспорта. Можно создать отдельную ветку с Windows-процессами.", color = routeMuted, fontSize = 12.sp)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text(when (block.kind) {
                                ConditionKind.DOMAIN -> "Домен или *.example.com"
                                ConditionKind.CIDR -> "Адрес или подсеть"
                                else -> "Имя процесса"
                            }) })
                        Button(onClick = {
                            onChange(block.copy(values = (block.values + splitRouteValues(input).map { PatternSign.signed(it, excludeNew || PatternSign.negated(it)) }).distinct()))
                            input = ""
                        }, enabled = input.isNotBlank()) { Text("Добавить") }
                    }
                    FilterChip(excludeNew, onClick = { excludeNew = !excludeNew }, label = { Text("Кроме указанных") })
                    if (block.kind == ConditionKind.PROCESS) {
                        TextButton(onClick = { onPickProcesses?.invoke() ?: run { processPicker = true } }) {
                            Text("Выбрать программу ▾")
                        }
                    }
                }
            }
            if (block.kind == ConditionKind.DOMAIN) Text("Точный домен или маска *.example.com.", color = routeMuted, fontSize = 12.sp)
        }
    }
    if (countryPicker) CountryPickerDialog(block.values.toSet(), onClose = { countryPicker = false },
        onConfirm = { onChange(block.copy(values = it)); countryPicker = false }, showPrivate = false)
    if (processPicker) ProcessPickerDialog(block.values.toSet(), onClose = { processPicker = false },
        onConfirm = { onChange(block.copy(values = it)); processPicker = false })
}

@Composable
internal fun CountryPickerDialog(initial: Set<String>, onClose: () -> Unit, onConfirm: (List<String>) -> Unit, showPrivate: Boolean = true) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial) }
    val labels = remember { CountryNames.all }
    val labelMap = remember(labels) { labels.associateBy { it.code } }
    val grouped = remember(query) { CountryCatalog.groups(query, emptyList(), labels) }
    val codes = remember(grouped) { (grouped.frequent + grouped.rest).distinct() }
    fun cycle(code: String) {
        val existing = selected.firstOrNull { PatternSign.body(it) == code }
        selected = selected.filterNot { PatternSign.body(it) == code }.toSet() + when {
            existing == null -> setOf(code)
            !PatternSign.negated(existing) -> setOf("!$code")
            else -> emptySet()
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Страны и сети") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Поиск страны") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(350.dp)) {
                    if (showPrivate) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable { cycle("private") }, verticalAlignment = Alignment.CenterVertically) {
                                val value = selected.firstOrNull { PatternSign.body(it) == "private" }
                                Text(when { value == null -> "○"; PatternSign.negated(value) -> "✕"; else -> "✓" },
                                    color = when { value == null -> routeMuted; PatternSign.negated(value) -> routeRed; else -> routeGreen },
                                    modifier = Modifier.width(40.dp).padding(start = 14.dp), fontSize = 20.sp)
                                Text("Частные сети", color = routeText)
                            }
                        }
                    }
                    items(codes) { code ->
                        val label = labelMap[code]
                        Row(Modifier.fillMaxWidth().clickable { cycle(code) }, verticalAlignment = Alignment.CenterVertically) {
                            val value = selected.firstOrNull { PatternSign.body(it) == code }
                            Text(when { value == null -> "○"; PatternSign.negated(value) -> "✕"; else -> "✓" },
                                color = when { value == null -> routeMuted; PatternSign.negated(value) -> routeRed; else -> routeGreen },
                                modifier = Modifier.width(40.dp).padding(start = 14.dp), fontSize = 20.sp)
                            Text(code.uppercase() + "  " + (label?.nameRu ?: code), color = routeText)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.sorted()) }) { Text("Выбрать") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

@Composable
private fun ProcessPickerDialog(initial: Set<String>, onClose: () -> Unit, onConfirm: (List<String>) -> Unit) {
    var search by remember { mutableStateOf("") }
    var available by remember { mutableStateOf<List<WindowsAppEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(initial) }
    var loading by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf("Все") }
    val scope = rememberCoroutineScope()
    fun toggle(process: String) {
        val existing = selected.firstOrNull { PatternSign.body(it).equals(process, ignoreCase = true) }
        selected = selected.filterNot { PatternSign.body(it).equals(process, ignoreCase = true) }.toSet() + if (existing == null) setOf(process) else emptySet()
    }
    LaunchedEffect(Unit) {
        available = withContext(Dispatchers.IO) { WindowsAppCatalog.load() }
        loading = false
    }
    val filtered = available.filter { entry ->
        (source == "Все" || source == "Запущенные" && entry.running || source == "Установленные" && entry.installed) &&
            (search.isBlank() || entry.label.contains(search, ignoreCase = true) || entry.processName.contains(search, ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Выбрать программу") },
        text = {
            Column {
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Название или имя .exe") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Все", "Запущенные", "Установленные").forEach { item ->
                        FilterChip(source == item, onClick = { source = item }, label = { Text(item) })
                    }
                }
                TextButton(onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { chooseWindowsExecutable() }
                        if (file != null) {
                            available = (available + WindowsAppEntry(file.nameWithoutExtension, file, installed = true))
                                .distinctBy { it.executable.absolutePath.lowercase() }
                            selected = selected + file.name
                            source = "Все"
                            search = file.nameWithoutExtension
                        }
                    }
                }) { Text("+ Указать файл .exe") }
                Text("Маршрут сопоставляет имя процесса. Выбранный файл помогает определить его и показать значок.",
                    color = routeMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(330.dp)) {
                    if (loading) item { Text("Ищем программы…", color = routeMuted) }
                    if (!loading && filtered.isEmpty()) item { Text("Программы не найдены. Укажите файл вручную.", color = routeMuted) }
                    items(filtered, key = { it.executable.absolutePath }) { entry ->
                        var icon by remember(entry.executable.absolutePath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                        LaunchedEffect(entry.executable.absolutePath) {
                            icon = withContext(Dispatchers.IO) { WindowsProcessIdentity.resolve(entry.executable.absolutePath).icon }
                        }
                        Row(Modifier.fillMaxWidth().clickable { toggle(entry.processName) }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected.any { PatternSign.body(it).equals(entry.processName, ignoreCase = true) },
                                onCheckedChange = { toggle(entry.processName) })
                            if (icon != null) Image(icon!!, null, Modifier.size(28.dp))
                            else Box(Modifier.size(28.dp).background(routeBorder, RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text(entry.label, color = routeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${entry.processName} · ${if (entry.running) "запущена" else "установлена"}",
                                    color = routeMuted, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.sorted()) }) { Text("Выбрать") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

private fun chooseWindowsExecutable(): File? {
    var chosen: File? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = "Выберите исполняемый файл программы"
            fileFilter = FileNameExtensionFilter("Программы (*.exe)", "exe")
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chosen = chooser.selectedFile?.takeIf { it.isFile && it.extension.equals("exe", ignoreCase = true) }
        }
    }
    return chosen
}

private fun splitRouteValues(value: String): List<String> = value.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
private fun ruleBreadcrumb(parentId: String?, rules: List<StoredRule>): String {
    val path = mutableListOf("Корень")
    val visited = mutableSetOf<String>()
    var current = parentId
    val reversed = mutableListOf<String>()
    while (current != null && visited.add(current)) {
        val rule = rules.firstOrNull { it.id == current } ?: break
        reversed += displayTitle(rule)
        current = rule.parentId
    }
    path += reversed.asReversed()
    return path.joinToString("  /  ")
}
private fun actionLabelDesktop(action: String) = when (action) { "DIRECT" -> app.lernet.engine.text.RouteWords.DIRECT; "BLOCK" -> app.lernet.engine.text.RouteWords.BLOCK; else -> app.lernet.engine.text.RouteWords.VIA }
private fun actionLabelDesktop(rule: StoredRule): String =
    if (rule.action == "PROXY" && rule.pipeName.isNotBlank()) "Соединение: ${rule.pipeName}" else actionLabelDesktop(rule.action)
private fun displayTitle(rule: StoredRule): String = rule.title.ifBlank { if (DesktopRouteTree.isElse(rule)) "Иначе" else "Правило ${rule.sortIndex + 1}" }
private fun ruleSummary(rule: StoredRule): String = app.lernet.engine.text.RouteWords.conditionSummary(
    rule.domains, rule.domainSuffixes, rule.cidrs, rule.countries, rule.processes, rule.apps)

@Composable
private fun RouteEmpty(title: String, description: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = routeText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(description, color = routeMuted, fontSize = 13.sp)
        }
    }
}

internal const val BASIC_BLOCK_EXPERT_ONLY =
    "Это правило запрета создано в прошлой версии и продолжает действовать: эти адреса закрыты. Новые запреты можно добавить только в режиме Эксперт."

/** Basic-mode actions; «Запретить» is listed only for an old ban rule being edited. */
internal fun basicDesktopActions(legacyBlock: Boolean): List<String> =
    if (legacyBlock) listOf("PROXY", "DIRECT", "BLOCK") else listOf("PROXY", "DIRECT")
