package app.lernet.ui.expert

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyTree
import app.lernet.ui.icons.LerNetSymbols

/** Actions stay with their node; menu closures never act on a different selection. */
@Composable
internal fun ExpertNodeActions(
    node: PolicyNode?, tree: PolicyTree, onEdit: () -> Unit, onAdd: () -> Unit,
    onOtherwise: () -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit,
) {
    var expanded by remember(tree.scope, node?.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onEdit, Modifier.weight(1f)) {
            Icon(LerNetSymbols.edit(), contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.expert_selected_properties))
        }
        Box {
            IconButton({ expanded = true }) {
                Icon(LerNetSymbols.more(), contentDescription = stringResource(R.string.expert_node_options))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.route_add_child)) },
                    enabled = node == null || ExpertEdits.canAddChild(node),
                    onClick = { expanded = false; onAdd() },
                )
                if (node != null && canAddMissingOtherwise(tree, node)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.expert_add_otherwise)) },
                        onClick = { expanded = false; onOtherwise() })
                }
                if (node != null && !PolicyOtherwise.isOtherwise(node)) {
                    listOf(-1 to R.string.expert_move_up, 1 to R.string.expert_move_down).forEach { (direction, label) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) },
                            enabled = PolicyBranchEditing.canMoveNode(tree, node.id, direction),
                            onClick = { expanded = false; onMove(direction) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.expert_remove)) },
                        onClick = { expanded = false; onDelete() })
                }
            }
        }
    }
}
