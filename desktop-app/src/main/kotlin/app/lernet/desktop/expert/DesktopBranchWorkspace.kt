package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.lernet.desktop.RouteEditorView
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyTree

@Composable
internal fun DesktopBranchWorkspace(
    tree: PolicyTree,
    branches: DesktopRuleBranches,
    selectedId: String?,
    onSelect: (PolicyNode?) -> Unit,
    targetName: (PolicyNode) -> String,
    inactiveReason: (PolicyNode) -> String?,
    canAdd: (PolicyNode?) -> Boolean,
    add: (PolicyNode?) -> Unit,
    move: (String, PolicyCanvasPoint) -> Unit,
    arrange: () -> Unit,
    modifier: Modifier = Modifier,
    layoutEnabled: Boolean = true,
    overlayOpen: Boolean = false,
    deleteSelected: (() -> Unit)? = null,
    menuItems: @Composable (close: () -> Unit) -> Unit = {},
    scopePicker: @Composable () -> Unit,
    listExtras: androidx.compose.foundation.lazy.LazyListScope.(select: (PolicyNode?) -> Unit) -> Unit = {},
    listBadges: @Composable (PolicyNode) -> Unit = {},
    reorder: ((PolicyNode, Int) -> Unit)? = null,
    fullView: @Composable (select: (PolicyNode?) -> Unit) -> Unit,
    inspector: @Composable (Modifier, close: (() -> Unit)?, focusId: String?, go: (String?) -> Unit) -> Unit,
) {
    var view by rememberSaveable { mutableStateOf(RouteEditorView.SCHEME) }
    var fullMap by rememberSaveable(tree.scope) { mutableStateOf(false) }
    var focusId by rememberSaveable(tree.scope) { mutableStateOf<String?>(null) }
    var expandedIds by rememberSaveable(tree.scope) { mutableStateOf(emptyList<String>()) }
    var collapsedOrigin by rememberSaveable(tree.scope) { mutableStateOf(false) }
    var cameraRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var mapMenu by remember { mutableStateOf(false) }
    var inspectorOpen by rememberSaveable(tree.scope) { mutableStateOf(false) }
    var query by remember(tree.scope) { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focus = remember { FocusRequester() }
    fun go(id: String?, selectId: String? = id) {
        focusId = id
        expandedIds = emptyList()
        collapsedOrigin = false
        onSelect(branches.byId[selectId])
        cameraRequest++
    }
    val graphProblem = remember(tree) { ExpertPolicyEditing.graphProblem(tree) }
    val actualFocus = focusId?.takeIf { it in branches.byId }
    fun changeFocus(id: String?) {
        go(id, branches.children[id]?.firstOrNull()?.id ?: id)
        fullMap = false
        view = RouteEditorView.SCHEME
    }
    fun showSelection(node: PolicyNode?) {
        onSelect(node)
        inspectorOpen = true
    }
    LaunchedEffect(selectedId, branches) {
        val selected = branches.byId[selectedId] ?: return@LaunchedEffect
        if (view == RouteEditorView.SCHEME &&
            !fullMap &&
            graphProblem == null &&
            selected.id != actualFocus &&
            (collapsedOrigin || branches.visible(actualFocus, expandedIds.toSet()).none { it.id == selected.id })
        ) {
            go(branches.path(selected.id).dropLast(1).lastOrNull()?.id, selected.id)
        }
    }
    BoxWithConstraints(
        modifier.focusRequester(focus).onPreviewKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown || searching || overlayOpen -> false
                event.key == Key.Slash -> {
                    searchFocus.requestFocus()
                    true
                }
                (event.key == Key.Delete || event.key == Key.Backspace) && deleteSelected != null -> {
                    deleteSelected()
                    true
                }
                else -> false
            }
        }.focusable()
    ) {
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
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val title: @Composable () -> Unit = {
                Column {
                    Text("Маршрутизация", color = ExpertColors.muted, fontSize = 12.sp)
                    Text(
                        "Схема правил", color = ExpertColors.text, fontSize = if (narrow) 24.sp else 30.sp,
                        lineHeight = 34.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (small) title()
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!small) Box(Modifier.weight(1f)) { title() }
                MapSearch(
                    branches, if (small) Modifier.weight(1f) else Modifier.width(if (narrow) 155.dp else 205.dp),
                    searchFocus, query, { query = it }, { searching = it }, selectFromSearch
                )
                val parent = branches.byId[actualFocus]
                MapButton(
                    if (narrow) "+ Правило" else "+ Добавить правило", { add(parent) },
                    primary = true, enabled = canAdd(parent)
                )
            }
            graphProblem?.let { ExpertMessage("Схема показана списком", it, warning = true) }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ExpertColors.border), color = ExpertColors.panel
                ) {
                    Column(Modifier.fillMaxSize().padding(1.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                        if (opened) onSelect(branches.byId[actualFocus])
                                        cameraRequest++
                                    })
                                }
                            }
                            Box {
                                val viewLabel = when {
                                    effectiveView == RouteEditorView.LIST -> "Список ▾"
                                    fullMap -> "Полная схема ▾"
                                    else -> "Граф ▾"
                                }
                                MapButton(
                                    viewLabel,
                                    { mapMenu = true }
                                )
                                DropdownMenu(mapMenu, { mapMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Граф веток") }, enabled = graphProblem == null,
                                        onClick = {
                                            mapMenu = false
                                            fullMap = false
                                            view = RouteEditorView.SCHEME
                                        }
                                    )
                                    DropdownMenuItem(text = { Text("Списком") }, onClick = {
                                        mapMenu = false
                                        view = RouteEditorView.LIST
                                    })
                                    DropdownMenuItem(
                                        text = { Text("Полная схема и каналы") }, enabled = graphProblem == null,
                                        onClick = {
                                            mapMenu = false
                                            fullMap = true
                                            view = RouteEditorView.SCHEME
                                            go(null)
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Упорядочить узлы") },
                                        enabled = tree.positions.isNotEmpty() && layoutEnabled,
                                        onClick = {
                                            mapMenu = false
                                            arrange()
                                            cameraRequest++
                                        }
                                    )
                                    menuItems { mapMenu = false }
                                }
                            }
                            scopePicker()
                        }
                        HorizontalDivider(color = ExpertColors.border)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                effectiveView == RouteEditorView.LIST -> DesktopBranchList(
                                    branches, selectedId, ::showSelection,
                                    targetName, inactiveReason, listBadges, reorder, listExtras
                                )
                                fullMap -> fullView(::showSelection)
                                else -> RuleBranchMap(
                                    tree, branches, targetName, inactiveReason, actualFocus, selectedId,
                                    expandedIds.toSet(), collapsedOrigin, cameraRequest,
                                    select = ::showSelection,
                                    fold = { id ->
                                        if (id == actualFocus) {
                                            if (!collapsedOrigin) onSelect(branches.byId[actualFocus])
                                            collapsedOrigin = !collapsedOrigin
                                        } else if (id != null) {
                                            val closing = id in expandedIds
                                            if (closing && branches.path(selectedId).any { it.id == id }) onSelect(branches.byId[id])
                                            expandedIds = if (closing) expandedIds - id else expandedIds + id
                                        }
                                    },
                                    move = move,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        HorizontalDivider(color = ExpertColors.border)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Правил в схеме · ${tree.nodes.size}", Modifier.weight(1f), color = ExpertColors.muted, fontSize = 12.sp)
                            if (actualFocus != null) MapButton("Вся схема", { changeFocus(null) })
                            if (narrow) MapButton("Свойства", { inspectorOpen = true })
                        }
                    }
                }
                if (!narrow) inspector(Modifier.width(inspectorWidth).wrapContentHeight(), null, actualFocus, ::changeFocus)
            }
        }
        if (narrow && inspectorOpen && !overlayOpen) {
            Dialog(onDismissRequest = { inspectorOpen = false }) {
                inspector(Modifier.widthIn(max = 390.dp).fillMaxWidth(), { inspectorOpen = false }, actualFocus, ::changeFocus)
            }
        }
    }
}
