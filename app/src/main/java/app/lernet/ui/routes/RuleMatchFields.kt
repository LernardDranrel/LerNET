package app.lernet.ui.routes

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteTerminal
import app.lernet.ui.icons.LerNetSymbols

@Composable
internal fun ActionChips(
    node: RuleNodeRecord,
    nodes: List<RuleNodeRecord>,
    namingNodeId: String?,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    val branching = nodes.any { it.parentId == node.id }
    var choosingTerminal by remember(node.id, branching) { mutableStateOf(false) }
    if (branching) {
        Text(stringResource(R.string.route_branch), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.route_branch_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChildRuleButton(node.id, onIntent)
        if (!choosingTerminal) {
            OutlinedButton(onClick = { choosingTerminal = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.route_branch_terminal))
            }
            return
        }
    }
    val choices = listOf(
        Triple("proxy", R.string.action_proxy, R.string.action_proxy_help),
        Triple("direct", R.string.action_direct, R.string.action_direct_help),
        Triple("block", R.string.action_block, R.string.action_block_help),
    )
    Text(stringResource(R.string.rule_section_action), style = MaterialTheme.typography.titleSmall)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (storage, label, _) ->
            SegmentedButton(
                selected = !branching && node.action.equals(storage, ignoreCase = true),
                onClick = {
                    if (branching || !node.action.equals(storage, ignoreCase = true)) {
                        onIntent(RouteEditorIntent.RequestOutcome(node.id, choiceFor(storage)))
                    }
                },
                label = { Text(stringResource(label)) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            )
        }
    }
    if (node.action.equals("proxy", ignoreCase = true)) {
        Text(stringResource(R.string.rule_section_mode), style = MaterialTheme.typography.titleSmall)
        ProxyModeChips(node, nodes, namingNodeId, onIntent)
    }
    val help = choices.firstOrNull { node.action.equals(it.first, ignoreCase = true) }?.third
        ?: R.string.action_proxy_help
    Text(
        stringResource(help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!branching) ChildRuleButton(node.id, onIntent)
}

@Composable
private fun ChildRuleButton(nodeId: String, onIntent: (RouteEditorIntent) -> Unit) {
    OutlinedButton(
        onClick = { onIntent(RouteEditorIntent.AddChild(nodeId)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(LerNetSymbols.add(), contentDescription = null)
        Text(stringResource(R.string.route_add_child))
    }
}

@Composable
private fun ProxyModeChips(
    node: RuleNodeRecord,
    nodes: List<RuleNodeRecord>,
    namingNodeId: String?,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    val kids = nodes.count { it.parentId == node.id }
    val parsed = RouteAction.entries.firstOrNull { it.name.equals(node.action, ignoreCase = true) } ?: return
    val stored = RouteTerminal.proxyMode(parsed, node.pipeName, kids)
    val shown = if (namingNodeId == node.id && node.pipeName.isBlank() && kids == 0) {
        RouteTerminal.ProxyMode.NAMED
    } else {
        stored
    }
    if (node.pipeName.isNotBlank() && kids > 0) {
        Text(stringResource(R.string.else_err_pipe_fork), color = MaterialTheme.colorScheme.error)
    }
    val options = buildList {
        add(RouteTerminal.ProxyMode.AUTO to OutcomeChoice.AUTO)
        add(RouteTerminal.ProxyMode.NAMED to OutcomeChoice.NAMED)
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, choice) ->
            SegmentedButton(
                selected = shown == mode,
                onClick = { onIntent(RouteEditorIntent.RequestOutcome(node.id, choice)) },
                label = { Text(stringResource(modeLabel(mode))) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            )
        }
    }
}

private fun choiceFor(storage: String): OutcomeChoice = when (storage) {
    "direct" -> OutcomeChoice.DIRECT
    "block" -> OutcomeChoice.BLOCK
    "proxy" -> OutcomeChoice.AUTO
    else -> OutcomeChoice.AUTO
}

private fun modeLabel(mode: RouteTerminal.ProxyMode): Int = when (mode) {
    RouteTerminal.ProxyMode.AUTO -> R.string.mode_auto
    RouteTerminal.ProxyMode.NAMED -> R.string.mode_named
    RouteTerminal.ProxyMode.FORK -> R.string.mode_fork
}

internal fun showsNamedPipe(node: RuleNodeRecord, nodes: List<RuleNodeRecord>, namingNodeId: String?): Boolean {
    if (!node.action.equals("proxy", ignoreCase = true)) return false
    if (node.pipeName.isNotBlank()) return true
    return namingNodeId == node.id && nodes.none { it.parentId == node.id }
}
