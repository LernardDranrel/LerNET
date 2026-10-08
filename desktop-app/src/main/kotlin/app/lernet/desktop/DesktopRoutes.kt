package app.lernet.desktop

import androidx.compose.runtime.key
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import app.lernet.routing.RouteLayoutNode
import app.lernet.routing.RouteTreeLayout
import app.lernet.routing.RouteCompiler
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionCodec
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.PatternSign
import app.lernet.routing.RuleConditions
import app.lernet.routing.RuleMatch
import app.lernet.ui.routes.CountryCatalog
import app.lernet.ui.routes.CountryNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
        saved.profiles.map { it.id to "Профиль: ${it.name}" }
    var ownerId by rememberSaveable(preferredOwnerId, saved.selectedProfileId) {
        mutableStateOf(preferredOwnerId?.takeIf { id -> owners.any { it.first == id } }
            ?: profile?.groupId?.let { "grp_$it" } ?: profile?.id ?: owners.firstOrNull()?.first)
    }
    if (ownerId == null) {
        RouteEmpty("Сначала выберите профиль", "Правила принадлежат конкретному профилю и применяются при следующем подключении.")
        return
    }
    val activeOwner = ownerId!!
    val ownerProfile = saved.profiles.firstOrNull { it.id == activeOwner }
    val ownerGroup = ownerProfile?.groupId?.let { id -> saved.groups.firstOrNull { it.id == id } }
    val initialDraft = remember(activeOwner) { controller.routeDraft(activeOwner) }
    var baseline by remember(activeOwner) { mutableStateOf(initialDraft.baseline) }
    var rules by remember(activeOwner) { mutableStateOf(initialDraft.rules) }
    val dirty = rules != baseline
    LaunchedEffect(activeOwner, saved.rules) {
        if (rules == baseline) {
            baseline = saved.rules.filter { it.profileId == activeOwner }
            rules = baseline
            controller.clearRuleDraft(activeOwner)
        }
    }
    var view by rememberSaveable(activeOwner) { mutableStateOf(RouteEditorView.SCHEME) }
    var selectedId by rememberSaveable(activeOwner) { mutableStateOf<String?>(null) }
    var rootSelected by rememberSaveable(activeOwner) { mutableStateOf(true) }
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
        rules = edit.rules
        controller.keepRuleDraft(activeOwner, baseline, rules)
        draftError = null
        return true
    }
    fun saveDraft(): Boolean {
        if (!dirty) return true
        if (!controller.commitRuleDraft(activeOwner, baseline, rules)) {
            draftError = "Не удалось сохранить черновик. Проверьте сообщение о состоянии приложения."
            return false
        }
        baseline = rules
        controller.clearRuleDraft(activeOwner)
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
    val preview = remember(rules, previewProfileId, saved.mode, saved.defaultDnsPolicy, saved.tunMtu, saved.xmuxConcurrency, saved.directDnsServer) {
        previewProfileId?.let { runCatching { controller.preview(it, draftRules = rules, draftOwnerId = activeOwner) }.getOrNull() }
    }

    val pageFocus = remember(activeOwner) { FocusRequester() }
    LaunchedEffect(activeOwner, view) { pageFocus.requestFocus() }
    Column(Modifier.fillMaxSize().focusRequester(pageFocus).focusable().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.Delete, Key.Backspace) &&
            editing == null && deleting == null && selected != null) {
            deleting = selected
            true
        } else false
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("ЧЬИ МАРШРУТЫ РЕДАКТИРУЕМ", color = routeMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(onClick = { ownerMenu = true }) {
                        Text((owners.firstOrNull { it.first == activeOwner }?.second ?: activeOwner) + "  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(ownerMenu, onDismissRequest = { ownerMenu = false }) {
                        saved.groups.forEach { group ->
                            DropdownMenuItem(text = { Text("▣  ${group.name}", fontWeight = FontWeight.SemiBold) },
                                onClick = { chooseOwner("grp_${group.id}") })
                            saved.profiles.filter { it.groupId == group.id }.forEach { child ->
                                DropdownMenuItem(text = {
                                    Column(Modifier.padding(start = 22.dp)) {
                                        Text("└  ${child.name}")
                                        Text("Действует маршрут папки", color = routeMuted, fontSize = 11.sp)
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
                Text("Маршруты · ${rules.size} правил · ${if (dirty) "есть несохранённый черновик" else "сохранённая версия"}", color = if (dirty) routeBlue else routeMuted, fontSize = 12.sp)
            }
            if (dirty) {
                OutlinedButton(onClick = { rules = baseline; selectedId = null; selectedChannel = null; draftError = null; controller.clearRuleDraft(activeOwner) }) { Text("Отменить") }
                Button(onClick = { saveDraft() }) { Text("Сохранить") }
            }
            RouteEditorViewToggle(view) { view = it }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { editing = newRule(activeOwner, null, rules) }) { Text("+ Правило") }
        }
        Spacer(Modifier.height(10.dp))
        if (draftError != null) {
            Text(draftError.orEmpty(), color = routeRed, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (ownerGroup != null) {
            Surface(color = Color(0xFF302B1D), shape = RoundedCornerShape(10.dp)) {
                Text("Профиль находится в папке «${ownerGroup.name}». Сейчас для него действует маршрут папки. Эти правила профиля сохранятся, но начнут работать только после переноса профиля из папки.",
                    color = Color(0xFFF5C16C), modifier = Modifier.padding(12.dp), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (preview != null && !preview.isValid) {
            val error = preview?.errors?.firstOrNull() ?: "Не удалось проверить правила"
            Surface(color = Color(0xFF402B30), shape = RoundedCornerShape(10.dp)) {
                Text(error, color = routeRed, modifier = Modifier.padding(10.dp), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).border(1.dp, routeBorder, RoundedCornerShape(16.dp))) {
                if (view == RouteEditorView.SCHEME) {
                    RouteScheme(activeOwner, rules, selectedId, selectedChannel, rootSelected = rootSelected,
                        onAddRoot = { editing = newRule(activeOwner, null, rules) },
                        onSelect = { selectedId = it; rootSelected = it == null; selectedChannel = null },
                        onSelectChannel = { selectedChannel = it; selectedId = null; rootSelected = false },
                        onAddChild = { editing = newRule(activeOwner, it.id, rules) }, onReorder = { id, target, after ->
                            applyEdit(DesktopRouteTree.reorder(rules, id, target, after))
                        })
                } else {
                    RouteList(activeOwner, rules, selectedId, selectedChannel, rootSelected = rootSelected,
                        onSelect = { selectedId = it; rootSelected = it == null; selectedChannel = null },
                        onSelectChannel = { selectedChannel = it; selectedId = null; rootSelected = false },
                        onAddChild = { editing = newRule(activeOwner, it.id, rules) }, onReorder = { id, target, after ->
                            applyEdit(DesktopRouteTree.reorder(rules, id, target, after))
                        })
                }
            }
            RouteInspector(
                selected = selected,
                selectedChannel = selectedChannel,
                rootSelected = rootSelected,
                onAddRoot = { editing = newRule(activeOwner, null, rules) },
                rules = rules,
                modifier = Modifier.width(302.dp).fillMaxHeight(),
                onEdit = { editing = it },
                onAddChild = { editing = newRule(activeOwner, it.id, rules) },
                onToggle = { applyEdit(DesktopRouteTree.save(rules, it.copy(enabled = !it.enabled))) },
                onDelete = { deleting = it },
            )
        }
    }
    editing?.let { draft ->
        RouteRuleEditor(
            draft = draft,
            rules = rules,
            onClose = { editing = null },
            onSave = { if (applyEdit(DesktopRouteTree.save(rules, it))) { selectedId = it.id; rootSelected = false; selectedChannel = null; editing = null } },
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
            text = { Text("Сохранить изменения маршрутов перед переходом?") },
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

private fun newRule(profileId: String, parentId: String?, rules: List<StoredRule>): StoredRule = StoredRule(
    id = UUID.randomUUID().toString(), profileId = profileId, parentId = parentId,
    sortIndex = (DesktopRouteTree.siblings(rules, profileId, parentId).maxOfOrNull { it.sortIndex } ?: -1) + 1,
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
    val root = RulePosition(layout.root.x, layout.root.y)
    val positions = layout.nodes.mapValues { (_, point) -> RulePosition(point.x, point.y) }
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
                RouteEditorNodeFace("Весь трафик", "Корень маршрутизации", "Выход выбранного профиля", routeBlue,
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
                        Text("Источников: ${pipeSources[name].orEmpty().size}", color = routeMuted, fontSize = 10.sp)
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
private fun RouteList(profileId: String, rules: List<StoredRule>, selectedId: String?,
    selectedChannel: String?, rootSelected: Boolean, onSelect: (String?) -> Unit, onSelectChannel: (String) -> Unit,
    onAddChild: (StoredRule) -> Unit, onReorder: (String, String, Boolean) -> Unit) {
    val ordered = remember(rules) { orderedTree(rules, profileId) }
    val channels = remember(rules, profileId) { namedChannelSources(rules, profileId) }
    val bounds = remember(profileId) { mutableStateMapOf<String, Rect>() }
    var draggingId by remember(profileId) { mutableStateOf<String?>(null) }
    var dragStart by remember(profileId) { mutableStateOf(Offset.Zero) }
    var dragDelta by remember(profileId) { mutableStateOf(Offset.Zero) }
    val dragPoint = if (draggingId != null) dragStart + dragDelta else null
    val dragged = rules.firstOrNull { it.id == draggingId }
    val hover = dragPoint?.let { point -> bounds.entries.firstOrNull { entry ->
        val candidate = rules.firstOrNull { it.id == entry.key }
        candidate != null && candidate.id != draggingId && candidate.parentId == dragged?.parentId &&
            !DesktopRouteTree.isElse(candidate) && entry.value.contains(point)
    }?.key }
    LazyColumn(Modifier.fillMaxSize().background(routeBackground).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { Text("КОРЕНЬ  /  весь трафик", color = if (rootSelected) routeBlue else routeMuted,
            fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(12.dp)) }
        items(ordered, key = { it.first.id }) { (rule, depth) ->
            val currentHover by rememberUpdatedState(hover)
            val currentDropPoint by rememberUpdatedState(dragPoint)
            val rowDrag = if (DesktopRouteTree.isElse(rule)) Modifier else Modifier.pointerInput(rule.id) {
                detectDragGestures(
                    onDragStart = { local ->
                        draggingId = rule.id
                        dragStart = (bounds[rule.id]?.topLeft ?: Offset.Zero) + local
                        dragDelta = Offset.Zero
                    },
                    onDragEnd = {
                        val target = currentHover
                        if (target != null) {
                            val pointY = currentDropPoint?.y ?: 0f
                            onReorder(rule.id, target, pointY > (bounds[target]?.center?.y ?: pointY))
                        }
                        draggingId = null
                        dragDelta = Offset.Zero
                    },
                    onDragCancel = { draggingId = null; dragDelta = Offset.Zero },
                ) { change, amount ->
                    change.consume()
                    dragDelta += amount
                }
            }
            val dragging = draggingId == rule.id
            val chosen = selectedId == rule.id
            val border = androidx.compose.foundation.BorderStroke(if (hover == rule.id || chosen) 2.dp else 1.dp,
                if (hover == rule.id || chosen) routeBlue else routeBorder)
            Box(Modifier.fillMaxWidth().padding(start = (depth * 24).coerceAtMost(144).dp)
                .zIndex(if (dragging) 10f else 0f)) {
                Surface(
                    color = if (chosen) Color(0xFF243056) else routeCard,
                    shape = RoundedCornerShape(11.dp), border = border,
                    modifier = Modifier.fillMaxWidth()
                        .onGloballyPositioned { bounds[rule.id] = it.routeEditorBounds() }
                        .graphicsLayer { alpha = if (dragging) .35f else 1f }
                        .then(rowDrag)
                        .clickable { onSelect(rule.id) },
                ) { RouteListRowContent(rule, rules, chosen, onAddChild) }
                if (dragging) {
                    Surface(
                        color = Color(0xFF303B5C), shape = RoundedCornerShape(11.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, routeBlue),
                        modifier = Modifier.fillMaxWidth().graphicsLayer {
                            translationX = dragDelta.x
                            translationY = dragDelta.y
                            shadowElevation = 18f
                        },
                    ) { RouteListRowContent(rule, rules, selected = chosen, onAddChild = onAddChild, addEnabled = false) }
                }
            }
        }
        if (channels.isNotEmpty()) {
            item(key = "channels-heading") {
                Text("КАНАЛЫ", color = routeMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 3.dp))
            }
            items(channels.entries.toList(), key = { "channel:${it.key}" }) { (name, sources) ->
                val chosen = selectedChannel == name
                Surface(
                    color = if (chosen) Color(0xFF302B1D) else routeCard,
                    shape = RoundedCornerShape(11.dp),
                    border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp,
                        if (chosen) routeAmber else routeBorder),
                    modifier = Modifier.fillMaxWidth().clickable { onSelectChannel(name) },
                ) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (sources.size > 1) {
                            ChannelPortalMark(sources.size)
                        } else Spacer(Modifier.size(18.dp))
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(name, color = routeText, fontWeight = FontWeight.Medium)
                            Text("Источников: ${sources.size}", color = routeMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteListRowContent(rule: StoredRule, rules: List<StoredRule>, selected: Boolean,
    onAddChild: (StoredRule) -> Unit, addEnabled: Boolean = true) {
    val unavailable = rule.id in DesktopRouteTree.platformInactiveIds(rules)
    Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${rule.sortIndex + 1}", color = if (unavailable) routeMuted else routeBlue, fontSize = 12.sp, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(displayTitle(rule), color = if (unavailable || !rule.enabled) routeMuted else routeText, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val childCount = rules.count { it.parentId == rule.id }
            Text("${ruleSummary(rule)}  ·  ${if (childCount > 0) "Развилка · $childCount ветвей" else actionLabelDesktop(rule)}",
                color = routeMuted, fontSize = 11.sp, maxLines = 1)
            if (unavailable) Text("Только Android · неактивно в Windows", color = routeMuted, fontSize = 11.sp)
        }
        if (selected) RouteListAddButton(onClick = { onAddChild(rule) }, enabled = addEnabled)
    }
}

@Composable
private fun RouteListAddButton(onClick: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.size(40.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            val middleX = size.width / 2f
            val middleY = size.height / 2f
            val stroke = 2.5.dp.toPx()
            drawLine(routeBlue, Offset(middleX, 0f), Offset(middleX, size.height), stroke)
            drawLine(routeBlue, Offset(0f, middleY), Offset(size.width, middleY), stroke)
        }
    }
}

@Composable
private fun RouteInspector(
    selected: StoredRule?, selectedChannel: String?, rootSelected: Boolean, onAddRoot: () -> Unit,
    rules: List<StoredRule>, modifier: Modifier,
    onEdit: (StoredRule) -> Unit, onAddChild: (StoredRule) -> Unit,
    onToggle: (StoredRule) -> Unit, onDelete: (StoredRule) -> Unit,
) {
    RouteEditorInspector(if (selectedChannel == null) "ПРАВИЛО" else "КАНАЛ", modifier) {
            if (selectedChannel != null) {
                val sources = namedChannelSources(rules, rules.firstOrNull()?.profileId.orEmpty())[selectedChannel].orEmpty()
                Text(selectedChannel, color = routeAmber, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sources.size > 1) {
                        ChannelPortalMark(sources.size)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Источников: ${sources.size}", color = routeMuted, fontSize = 12.sp)
                }
                HorizontalDivider(color = routeBorder)
                sources.forEachIndexed { index, source ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${index + 1}. ${displayTitle(source)}", color = routeText,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(ruleBreadcrumb(source.parentId, rules), color = routeMuted, fontSize = 11.sp)
                    }
                }
            } else if (rootSelected) {
                Text("Весь трафик", color = routeText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Корень маршрутизации. Правила проверяются сверху вниз; иначе используется выход выбранного профиля.", color = routeMuted, fontSize = 12.sp)
                OutlinedButton(onClick = onAddRoot) { Text("+ Дочернее правило") }
            } else if (selected == null) {
                Text("Выберите блок на схеме или строку в списке", color = routeText, fontSize = 16.sp)
                Text("Выделите блок и нажмите + под ним. Порядок одноуровневых правил меняется перетаскиванием.", color = routeMuted, fontSize = 12.sp)
            } else {
                Text(displayTitle(selected), color = routeText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                val platformNote = DesktopRouteTree.platformNote(selected, rules)
                if (platformNote != null) Text(platformNote, color = routeMuted, fontSize = 12.sp)
                Text("Приоритет ${selected.sortIndex + 1} · ${if (platformNote != null) "Неактивно в Windows" else if (selected.enabled) "Включено" else "Выключено"}", color = routeMuted, fontSize = 12.sp)
                HorizontalDivider(color = routeBorder)
                Text(ruleBreadcrumb(selected.parentId, rules), color = routeMuted, fontSize = 11.sp)
                Text(ruleSummary(selected), color = routeText, fontSize = 13.sp)
                val childCount = rules.count { it.parentId == selected.id }
                Text(if (childCount > 0) "Развилка · исход выбирают дочерние правила" else actionLabelDesktop(selected),
                    color = if (childCount > 0) routeBlue else when (selected.action) { "DIRECT" -> routeGreen; "BLOCK" -> routeRed; else -> if (selected.pipeName.isNotBlank()) routeAmber else routeBlue })
                Button(onClick = { onEdit(selected) }, modifier = Modifier.fillMaxWidth()) { Text("Редактировать") }
                OutlinedButton(onClick = { onAddChild(selected) }, modifier = Modifier.fillMaxWidth()) { Text("+ Дочернее правило") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Включено", color = routeText, modifier = Modifier.weight(1f))
                    Switch(selected.enabled && platformNote == null, onCheckedChange = { onToggle(selected) },
                        enabled = !DesktopRouteTree.isElse(selected) && platformNote == null)
                }
                TextButton(onClick = { onDelete(selected) }) { Text("Удалить правило", color = routeRed) }
            }
    }
}

@Composable
private fun RouteRuleEditor(draft: StoredRule, rules: List<StoredRule>, onClose: () -> Unit, onSave: (StoredRule) -> Unit) {
    val wasElse = rules.firstOrNull { it.id == draft.id }?.let(DesktopRouteTree::isElse) == true
    var title by rememberSaveable(draft.id) { mutableStateOf(draft.title) }
    var parentId by rememberSaveable(draft.id) { mutableStateOf(draft.parentId) }
    var priority by rememberSaveable(draft.id) { mutableIntStateOf(draft.sortIndex) }
    var conditions by rememberSaveable(draft.id, stateSaver = routeEditorStateSaver<RuleConditions>()) { mutableStateOf(
        ConditionCodec.decode(draft.blocksJson, RuleMatch(
            apps = draft.apps,
            domains = draft.domains,
            domainSuffixes = draft.domainSuffixes,
            ipCidrs = draft.cidrs,
            geoip = draft.countries,
            processes = draft.processes,
        )).let { decoded ->
            if (draft.blocksJson.isBlank()) decoded.copy(join = MatchJoin.entries.firstOrNull { it.name == draft.join } ?: decoded.join)
            else decoded
        }
    ) }
    var action by rememberSaveable(draft.id) { mutableStateOf(draft.action.uppercase()) }
    var namedPipe by rememberSaveable(draft.id) { mutableStateOf(draft.pipeName.isNotBlank()) }
    var pipeName by rememberSaveable(draft.id) { mutableStateOf(draft.pipeName) }
    var parentMenu by remember { mutableStateOf(false) }
    var addBlockMenu by remember { mutableStateOf(false) }
    val currentRule = draft.copy(parentId = parentId, blocksJson = ConditionCodec.encode(conditions))
    val platformNote = DesktopRouteTree.platformNote(currentRule, rules.filterNot { it.id == draft.id } + currentRule)
    val candidates = rules.filter { it.id != draft.id && it.id !in descendants(rules, draft.id) }
    val hasChildren = rules.any { it.parentId == draft.id }
    val maxPriority = rules.count { it.profileId == draft.profileId && it.parentId == parentId && it.id != draft.id && !DesktopRouteTree.isElse(it) }
    val conditionErrors = if (wasElse) emptyList() else RouteCompiler.validateConditions(draft.id, conditions).map { it.message }
    val valid = conditionErrors.isEmpty() && (wasElse || (conditions.blocks.isNotEmpty() && conditions.blocks.all { block -> block.values.any(String::isNotBlank) })) &&
        (hasChildren || action != "PROXY" || !namedPipe || pipeName.trim().isNotBlank())
    fun save() {
        val projection = ConditionCodec.project(conditions)
        onSave(draft.copy(
            title = title.trim(), parentId = parentId, sortIndex = priority.coerceIn(0, maxPriority), action = action,
            pipeName = if (!hasChildren && action == "PROXY" && namedPipe) pipeName.trim() else "", join = conditions.join.name,
            domains = if (wasElse) emptyList() else projection.domains,
            domainSuffixes = if (wasElse) emptyList() else projection.domainSuffixes,
            cidrs = if (wasElse) emptyList() else projection.ipCidrs,
            countries = if (wasElse) emptyList() else projection.geoip,
            processes = if (wasElse) emptyList() else projection.processes,
            apps = if (wasElse) emptyList() else projection.apps,
            blocksJson = if (wasElse) "" else ConditionCodec.encode(conditions),
        ))
    }
    RouteEditorModal(if (wasElse) "Правило «Иначе»" else "Редактор правила", onClose, ::save, valid, "В черновик") {
        OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (platformNote != null) Text(platformNote, color = routeMuted, fontSize = 12.sp)
        Box {
            OutlinedButton(onClick = { parentMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Родитель: " + (parentId?.let { id -> rules.firstOrNull { it.id == id }?.let(::displayTitle) } ?: "Корень") + " ▾")
            }
            DropdownMenu(parentMenu, onDismissRequest = { parentMenu = false }) {
                DropdownMenuItem(text = { Text("Корень") }, onClick = { parentId = null; priority = rules.count { it.profileId == draft.profileId && it.parentId == null && it.id != draft.id && !DesktopRouteTree.isElse(it) }; parentMenu = false })
                candidates.forEach { candidate -> DropdownMenuItem(text = { Text(displayTitle(candidate)) }, onClick = { parentId = candidate.id; priority = rules.count { it.profileId == draft.profileId && it.parentId == candidate.id && it.id != draft.id && !DesktopRouteTree.isElse(it) }; parentMenu = false }) }
            }
        }
        Text(ruleBreadcrumb(parentId, rules), color = routeMuted, fontSize = 11.sp)
        if (!wasElse) {
            Text("Приоритет ${priority.coerceIn(0, maxPriority) + 1} · перетащите блок на схеме или в списке, чтобы изменить порядок",
                color = routeMuted, fontSize = 12.sp)
        }
        if (!wasElse) {
            Surface(color = routeCard, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, routeBorder)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("УСЛОВИЯ", color = routeMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("Внутри блока значения работают как ИЛИ. Между блоками выберите логику ниже.", color = routeMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(conditions.join == MatchJoin.OR, onClick = { conditions = conditions.copy(join = MatchJoin.OR) }, label = { Text("ИЛИ") })
                        FilterChip(conditions.join == MatchJoin.AND, onClick = { conditions = conditions.copy(join = MatchJoin.AND) }, label = { Text("И") })
                    }
                }
            }
            conditions.blocks.forEachIndexed { index, block ->
                key(index, block.kind) {
                    RouteConditionBlockEditor(
                        block = block,
                        onChange = { next -> conditions = conditions.copy(blocks = conditions.blocks.toMutableList().also { it[index] = next }) },
                        onRemove = { conditions = conditions.copy(blocks = conditions.blocks.filterIndexed { i, _ -> i != index }) },
                    )
                }
            }
            Box {
                OutlinedButton(onClick = { addBlockMenu = true }) { Text("+ Условие") }
                DropdownMenu(addBlockMenu, onDismissRequest = { addBlockMenu = false }) {
                    listOf(ConditionKind.DOMAIN, ConditionKind.GEOIP, ConditionKind.PRIVATE, ConditionKind.CIDR, ConditionKind.PROCESS).forEach { kind ->
                        DropdownMenuItem(text = { Text(conditionKindTitle(kind)) }, onClick = {
                            conditions = conditions.copy(blocks = conditions.blocks + ConditionBlock(kind, if (kind == ConditionKind.PRIVATE) listOf("private") else emptyList()))
                            addBlockMenu = false
                        })
                    }
                }
            }
            if (!valid) Text(conditionErrors.joinToString("\n").ifBlank { "Добавьте хотя бы одно значение в каждый блок условия." }, color = routeRed, fontSize = 12.sp)
        } else Text("Правило срабатывает, когда ни одно предыдущее правило этого уровня не подошло.", color = routeMuted)
        if (hasChildren) {
            Text("РАЗВИЛКА", color = routeBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text("Этот блок проверяет условие. Действие задают его дочерние правила, включая обязательное «Иначе».", color = routeMuted, fontSize = 12.sp)
        } else {
            Text("ДЕЙСТВИЕ", color = routeMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("PROXY", "DIRECT", "BLOCK").forEach { value ->
                    FilterChip(action == value && (value != "PROXY" || !namedPipe),
                        onClick = { action = value; namedPipe = false }, label = { Text(actionLabelDesktop(value)) })
                }
                FilterChip(action == "PROXY" && namedPipe,
                    onClick = { action = "PROXY"; namedPipe = true }, label = { Text("Отдельный канал") })
            }
            if (action == "PROXY" && namedPipe) {
                OutlinedTextField(pipeName, { pipeName = it }, label = { Text("Название канала") },
                    supportingText = { Text("Одинаковое имя направляет правила в один канал") },
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
                Text("Нажмите значение, чтобы переключить его на отрицательное условие. × удаляет значение.", color = routeMuted, fontSize = 11.sp)
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
                    color = routeMuted, fontSize = 11.sp)
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
                                    color = routeMuted, fontSize = 11.sp, maxLines = 1)
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
private fun actionLabelDesktop(action: String) = when (action) { "DIRECT" -> "Напрямую"; "BLOCK" -> "Запретить"; else -> "Через VPN" }
private fun actionLabelDesktop(rule: StoredRule): String =
    if (rule.action == "PROXY" && rule.pipeName.isNotBlank()) "Канал: ${rule.pipeName}" else actionLabelDesktop(rule.action)
private fun displayTitle(rule: StoredRule): String = rule.title.ifBlank { if (DesktopRouteTree.isElse(rule)) "Иначе" else "Правило ${rule.sortIndex + 1}" }
private fun ruleSummary(rule: StoredRule): String = (rule.domains + rule.domainSuffixes.map { "*.$it" } + rule.cidrs + rule.countries + rule.processes)
    .take(3).joinToString(" · ").ifBlank { "Любой трафик" }

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
