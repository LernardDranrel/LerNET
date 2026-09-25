package app.lernet.engine

import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.ConditionCodec
import app.lernet.routing.RouteAction
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode

fun RuleNodeRecord.toRuleNode(): RuleNode {
    val match = RuleMatch(apps, domains, domainSuffixes, ipCidrs, geoip, processes)
    return RuleNode(
        id = id,
        parentId = parentId,
        enabled = enabled,
        sortIndex = sortIndex,
        match = match,
        action = RouteAction.fromStorage(action),
        pipeName = pipeName,
        conditions = blocksJson.takeIf { it.isNotBlank() }?.let { ConditionCodec.decode(it, match) },
    )
}
