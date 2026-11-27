package app.lernet.routing.policy

import app.lernet.routing.RouteTree
import app.lernet.routing.RuleConditions

/** Read-only input for the branch editor. VPN edits go to the original rule by ID. */
fun vpnRuleBranch(
    id: String,
    parentId: String?,
    sortIndex: Int,
    title: String,
    enabled: Boolean,
    conditions: RuleConditions,
    otherwise: Boolean,
    action: String,
    pipeName: String,
): PolicyNode = PolicyNode(
    id = id, parentId = parentId, sortIndex = sortIndex, title = title, enabled = enabled,
    conditions = conditions, otherwise = otherwise, detached = parentId == RouteTree.ORPHAN,
    target = when {
        action.equals("direct", true) -> PolicyTarget.Direct
        action.equals("block", true) -> PolicyTarget.Block
        pipeName.isNotBlank() -> PolicyTarget.Channel(pipeName)
        else -> PolicyTarget.CurrentExit
    },
)

fun vpnOwnerScope(ownerId: String): PolicyScope = if (ownerId.startsWith("grp_")) {
    PolicyScope.Folder(ownerId.removePrefix("grp_"))
} else {
    PolicyScope.Profile(ownerId)
}
