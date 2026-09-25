package app.lernet.ui.routes

import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.ConditionCodec
import app.lernet.routing.RuleConditions
import app.lernet.routing.RuleMatch

internal fun RuleNodeRecord.shownConditions(): RuleConditions {
    val match = RuleMatch(apps, domains, domainSuffixes, ipCidrs, geoip, processes)
    return ConditionCodec.decode(blocksJson, match)
}

internal fun RuleNodeRecord.withConditions(conditions: RuleConditions): RuleNodeRecord {
    val flat = ConditionCodec.project(conditions)
    return copy(
        blocksJson = ConditionCodec.encode(conditions),
        apps = flat.apps,
        domains = flat.domains,
        domainSuffixes = flat.domainSuffixes,
        ipCidrs = flat.ipCidrs,
        geoip = flat.geoip,
        processes = flat.processes,
    )
}
