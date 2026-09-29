package app.lernet.config.repo

import app.lernet.config.transfer.TransferRule
import app.lernet.routing.ConditionCodec
import app.lernet.routing.MatchJoin
import app.lernet.routing.RuleMatch

private fun TransferRule.match() = RuleMatch(
    apps = apps,
    domains = domains,
    domainSuffixes = domainSuffixes,
    ipCidrs = ipCidrs,
    geoip = geoip,
    processes = processes,
)

/** Android stores joins in blocksJson; older desktop rules can carry only flat fields + join. */
internal fun TransferRule.androidConditionsJson(): String {
    if (blocksJson.isNotBlank()) return blocksJson
    val conditions = ConditionCodec.fromMatch(match())
    if (conditions.blocks.isEmpty()) return ""
    val requestedJoin = MatchJoin.entries.firstOrNull { it.name.equals(join, ignoreCase = true) }
        ?: conditions.join
    return ConditionCodec.encode(conditions.copy(join = requestedJoin))
}
