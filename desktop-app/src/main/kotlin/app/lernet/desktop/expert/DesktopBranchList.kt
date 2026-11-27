package app.lernet.desktop.expert

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lernet.desktop.routeEditorBounds
import app.lernet.desktop.routeEditorDrag
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyTarget

@Composable
internal fun DesktopBranchList(
    branches: DesktopRuleBranches,
    selectedId: String?,
    select: (PolicyNode?) -> Unit,
    target: (PolicyNode) -> String,
    inactive: (PolicyNode) -> String?,
    badges: @Composable (PolicyNode) -> Unit,
    reorder: ((PolicyNode, Int) -> Unit)?,
    extras: LazyListScope.((PolicyNode?) -> Unit) -> Unit,
) {
    val rows = remember(branches) {
        val rows = mutableListOf<Pair<PolicyNode, Int>>()
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque<Pair<PolicyNode, Int>>()
        fun walk(root: PolicyNode) {
            pending.addLast(root to 0)
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeLast()
                if (!seen.add(node.id)) continue
                rows += node to depth
                branches.children[node.id].orEmpty().asReversed().forEach { pending.addLast(it to depth + 1) }
            }
        }
        branches.children[null].orEmpty().forEach(::walk)
        branches.ordered.filterNot { it.id in seen }.forEach(::walk)
        rows
    }
    val bounds = remember(branches) { mutableStateMapOf<String, Rect>() }
    var held by remember(branches) { mutableStateOf<PolicyNode?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val drop = held?.let { source ->
        branches.children[source.parentId].orEmpty().firstOrNull { other ->
            other.id != source.id && !other.otherwise && bounds[other.id]?.contains(pointer) == true
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        item(key = PolicyCanvasKeys.ROOT) {
            Text(
                "Весь трафик", Modifier.fillMaxWidth().selectable(selectedId == null, role = Role.Button) { select(null) }.padding(8.dp),
                color = ExpertColors.text, fontWeight = FontWeight.Medium
            )
        }
        items(rows, key = { PolicyCanvasKeys.node(it.first.id) }) { (node, depth) ->
            val reason = inactive(node) ?: if (node.detached) {
                "Отсоединена"
            } else if (!node.enabled) {
                "Выключена"
            } else {
                null
            }
            val count = branches.children[node.id].orEmpty().size
            val kind = when {
                count > 0 -> BranchGlyph.BRANCH
                node.target == PolicyTarget.Block -> BranchGlyph.BLOCK
                else -> BranchGlyph.EXIT
            }
            val drag = if (reorder == null || node.otherwise) {
                Modifier
            } else {
                Modifier.routeEditorDrag(
                    node.id,
                    onStart = {
                        held = node
                        pointer = (bounds[node.id]?.topLeft ?: Offset.Zero) + it
                    },
                    onDrag = { pointer += it }, onEnd = {
                        if (drop != null) {
                            val peers = branches.children[node.parentId].orEmpty()
                            val from = peers.indexOf(node)
                            val slot = peers.indexOf(drop) + if (pointer.y > bounds.getValue(drop.id).center.y) 1 else 0
                            val delta = slot - from - if (slot > from) 1 else 0
                            if (delta != 0) reorder(node, delta)
                        }
                        held = null
                    }, onCancel = { held = null }
                )
            }
            DisposableEffect(branches, node.id) { onDispose { bounds.remove(node.id) } }
            val active = node.id == selectedId || node == drop
            Surface(
                Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(6) * 16).dp),
                color = if (active) ExpertColors.blue.copy(alpha = .13f) else ExpertColors.panel,
                shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (active) ExpertColors.blue else ExpertColors.border)
            ) {
                Row(
                    Modifier.fillMaxWidth().onGloballyPositioned { bounds[node.id] = it.routeEditorBounds() }
                        .then(drag).selectable(node.id == selectedId, role = Role.Button) { select(node) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val iconColor = when {
                        reason != null -> ExpertColors.muted
                        kind == BranchGlyph.EXIT -> ExpertColors.green
                        else -> ExpertColors.blue
                    }
                    BranchIcon(kind, iconColor)
                    Column(Modifier.weight(1f)) {
                        Text(
                            policyNodeTitle(node), color = ExpertColors.text, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            ExpertPolicyEditing.conditionSummary(node), color = ExpertColors.muted, fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            reason ?: if (count > 0) "Вложенные правила · " + count else target(node),
                            color = if (reason == null) ExpertColors.blue else ExpertColors.muted, fontSize = 13.sp
                        )
                    }
                    badges(node)
                }
            }
        }
        extras(select)
    }
}

@Composable
internal fun BranchListChannel(name: String, sources: String, target: String, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open).padding(10.dp)) {
        Text("Канал · " + name, color = ExpertColors.amber, fontWeight = FontWeight.Medium)
        Text("Входящие ветки · " + sources.ifBlank { "нет" }, color = ExpertColors.muted, fontSize = 13.sp)
        Text(target, color = ExpertColors.text, fontSize = 13.sp)
    }
}
