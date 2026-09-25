package app.lernet.ui.routes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.RuleMatch

@Composable
internal fun RouteRuleExtras(
    node: RuleNodeRecord,
    nodes: List<RuleNodeRecord>,
    namingNodeId: String?,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    val match = RuleMatch(node.apps, node.domains, node.domainSuffixes, node.ipCidrs, node.geoip, node.processes)
    if (node.processes.isNotEmpty()) {
        Text("Условие Windows-процесса сохранено для переноса. На Android настройте для этого правила приложение отдельно.",
            color = MaterialTheme.colorScheme.error)
    }
    if (match.isCatchAll() && node.pipeName.isNotBlank()) {
        Text(stringResource(R.string.route_catchall_pipe), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showsNamedPipe(node, nodes, namingNodeId)) {
        OutlinedTextField(
            value = node.pipeName,
            onValueChange = { onIntent(RouteEditorIntent.Update(node.copy(pipeName = it.trim()))) },
            label = { Text(stringResource(R.string.route_pipe)) },
            supportingText = { Text(stringResource(R.string.route_pipe_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    ParentChips(node, nodes, onIntent)
}

@Composable
private fun ParentChips(
    node: RuleNodeRecord,
    nodes: List<RuleNodeRecord>,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val banned = descendants(nodes, node.id) + node.id
    val parents = nodes.filter { it.canAdoptChild() && it.id !in banned }
    val illegalKids = !node.acceptsChildren(nodes) && nodes.any { it.parentId == node.id }
    Text(stringResource(R.string.route_parent), style = MaterialTheme.typography.titleSmall)
    if (RouteFolders.misplaced(nodes, node) || illegalKids) {
        Text(stringResource(R.string.route_terminal), color = MaterialTheme.colorScheme.error)
    }
    val root = stringResource(R.string.route_parent_root)
    val chain = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var cursor = node.parentId
    while (cursor != null && seen.add(cursor)) {
        val parent = nodes.firstOrNull { it.id == cursor } ?: break
        chain.add(ruleHeadline(parent))
        cursor = parent.parentId
    }
    Text(
        (listOf(root) + chain.asReversed()).joinToString("  ›  "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(chain.firstOrNull() ?: root, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(root) }, onClick = {
                expanded = false
                onIntent(RouteEditorIntent.Reparent(node.id, null))
            })
            parents.forEach { parent ->
                DropdownMenuItem(text = {
                    Text(ruleHeadline(parent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, onClick = {
                    expanded = false
                    onIntent(RouteEditorIntent.Reparent(node.id, parent.id))
                })
            }
        }
    }
}
