package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyNode

@Composable
internal fun MapButton(label: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    val shape = RoundedCornerShape(7.dp)
    Surface(color = if (primary) ExpertColors.blue else ExpertColors.panel, shape = shape,
        border = if (primary) null else BorderStroke(1.dp, ExpertColors.border)) {
        Box(Modifier.clip(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 32.dp).padding(horizontal = 11.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
            Text(label, color = when {
                !enabled -> ExpertColors.muted.copy(alpha = .65f)
                primary -> ExpertColors.background
                else -> ExpertColors.text
            }, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun MapSearch(
    branches: DesktopRuleBranches, modifier: Modifier, requester: FocusRequester, query: String,
    setQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, choose: (PolicyNode) -> Unit,
) {
    val results = remember(branches, query) {
        val text = query.trim()
        if (text.isEmpty()) emptyList() else branches.ordered.filter {
            policyNodeTitle(it).contains(text, true) || ExpertPolicyEditing.conditionSummary(it).contains(text, true)
        }
    }
    var hit by remember(query) { mutableIntStateOf(0) }
    val resultsScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(hit, results) { if (hit in results.indices) resultsScroll.scrollToItem(hit) }
    Box(modifier) {
        Surface(color = ExpertColors.panel, border = BorderStroke(1.dp, ExpertColors.border), shape = RoundedCornerShape(7.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SearchMark()
                BasicTextField(query, setQuery, Modifier.weight(1f).focusRequester(requester).onFocusChanged { onFocus(it.isFocused) }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || query.isEmpty()) false else when (event.key) {
                            Key.Escape -> { setQuery(""); true }
                            Key.DirectionDown -> { hit = (hit + 1).coerceAtMost(results.lastIndex).coerceAtLeast(0); true }
                            Key.DirectionUp -> { hit = (hit - 1).coerceAtLeast(0); true }
                            Key.Enter, Key.NumPadEnter -> { results.getOrNull(hit)?.let(choose); true }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = "Найти правило" }, singleLine = true, cursorBrush = SolidColor(ExpertColors.blue),
                    textStyle = TextStyle(color = ExpertColors.text, fontSize = 13.sp),
                    decorationBox = { inner ->
                        Box { if (query.isEmpty()) Text("Найти правило", fontSize = 13.sp, color = ExpertColors.muted); inner() }
                    })
                if (query.isNotEmpty()) Text("×", Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) { setQuery("") }.semantics { contentDescription = "Очистить поиск" }.padding(4.dp),
                    color = ExpertColors.muted)
            }
        }
        if (query.isNotBlank()) Box(Modifier.align(Alignment.BottomStart)) {
            Popup(Alignment.TopStart, onDismissRequest = { setQuery("") }, properties = PopupProperties(focusable = false)) {
                Surface(Modifier.width(306.dp), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, ExpertColors.border),
                    color = ExpertColors.panel, shadowElevation = 6.dp) {
                    LazyColumn(Modifier.heightIn(max = 270.dp).padding(4.dp), state = resultsScroll) {
                        if (results.isEmpty()) item { Text("Совпадений нет", Modifier.padding(10.dp), color = ExpertColors.muted) }
                        items(results, key = { it.id }) { node ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                .background(if (results.getOrNull(hit)?.id == node.id) ExpertColors.blue.copy(alpha = .11f)
                                    else androidx.compose.ui.graphics.Color.Transparent).clickable(role = Role.Button) { choose(node) }
                                .padding(horizontal = 9.dp, vertical = 7.dp)) {
                                Text(policyNodeTitle(node), color = ExpertColors.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text((listOf("Вся схема") + branches.path(node.id).dropLast(1).map(::policyNodeTitle)).joinToString(" › "),
                                    color = ExpertColors.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMark() {
    androidx.compose.foundation.Canvas(Modifier.size(15.dp)) {
        val unit = size.width / 16f
        val center = androidx.compose.ui.geometry.Offset(6.5f * unit, 6.5f * unit)
        drawCircle(ExpertColors.muted, 4.8f * unit, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * unit))
        drawLine(ExpertColors.muted, center * 1.55f, androidx.compose.ui.geometry.Offset(14 * unit, 14 * unit), 1.5f * unit)
    }
}

@Composable
internal fun MapBreadcrumbs(path: List<PolicyNode>, go: (String?) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(path.lastOrNull()?.id, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Text("Вся схема", Modifier.clickable(enabled = path.isNotEmpty(), role = Role.Button) { go(null) }.padding(6.dp),
            color = if (path.isEmpty()) ExpertColors.text else ExpertColors.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        path.forEachIndexed { index, node ->
            Text("›", color = ExpertColors.muted)
            Text(policyNodeTitle(node), Modifier.widthIn(max = 190.dp).clickable(enabled = index < path.lastIndex, role = Role.Button) { go(node.id) }
                .padding(6.dp), color = if (index == path.lastIndex) ExpertColors.text else ExpertColors.blue,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun RuleInspector(
    title: String, subtitle: String, modifier: Modifier, close: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = ExpertColors.panel,
        border = BorderStroke(1.dp, ExpertColors.border)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, Modifier.weight(1f), color = ExpertColors.muted, fontSize = 12.sp)
                if (close != null) MapButton("Закрыть", close)
            }
            HorizontalDivider(color = ExpertColors.border)
            Text(title, color = ExpertColors.text, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun InspectorTrail(path: List<PolicyNode>, go: (String?) -> Unit) {
    Text("Путь в схеме", color = ExpertColors.muted, fontSize = 12.sp)
    Column {
        val steps = listOf(null) + path
        steps.forEachIndexed { index, node ->
            val current = index == steps.lastIndex
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                .clickable(enabled = !current, role = Role.Button) { go(node?.id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(5.dp).background(if (current) ExpertColors.blue else ExpertColors.muted,
                    androidx.compose.foundation.shape.CircleShape))
                Text(node?.let(::policyNodeTitle) ?: "Весь трафик", color = if (current) ExpertColors.text else ExpertColors.muted,
                    fontSize = 13.sp, lineHeight = 16.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
internal fun InspectorValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = ExpertColors.muted, fontSize = 12.sp)
        Text(value, color = ExpertColors.text, fontSize = 14.sp, lineHeight = 18.sp)
    }
}

@Composable
internal fun ScopePicker(state: ExpertUiState, select: (PolicyScope) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    fun open(scope: PolicyScope) { menu = false; select(scope) }
    val grouped = remember(state.profiles) { state.profiles.groupBy { it.folderId } }
    val folderIds = remember(state.folders) { state.folders.mapTo(mutableSetOf()) { it.id } }
    Box(Modifier.widthIn(max = 165.dp)) {
        MapButton(ExpertPolicyEditing.scopeName(state.selectedScope, state) + " ▾", { menu = true })
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Всё устройство", fontWeight = FontWeight.SemiBold) }, onClick = { open(PolicyScope.Device) })
            state.folders.forEach { folder ->
                DropdownMenuItem(text = { Text("Папка: ${folder.name}", fontWeight = FontWeight.SemiBold) },
                    onClick = { open(PolicyScope.Folder(folder.id)) })
                grouped[folder.id].orEmpty().forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.name, Modifier.padding(start = 18.dp)) }, onClick = { open(PolicyScope.Profile(profile.id)) })
                }
            }
            state.profiles.filter { it.folderId !in folderIds }.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name) }, onClick = { open(PolicyScope.Profile(profile.id)) })
            }
        }
    }
}
