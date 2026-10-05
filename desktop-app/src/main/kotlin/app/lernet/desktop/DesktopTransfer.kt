package app.lernet.desktop

import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferPoint
import app.lernet.config.transfer.TransferProfile
import app.lernet.config.transfer.TransferRule
import java.util.UUID

/** Copies are imported with new IDs, so an archive never overwrites existing data. */
object DesktopTransfer {
    private fun newId() = UUID.randomUUID().toString()
    private fun groupOwner(id: String) = "grp_$id"

    /** The shared workspace import has already remapped IDs exactly once. */
    fun materializeWorkspace(saved: StoredState, bundle: TransferBundle): StoredState {
        TransferCodec.validate(bundle)
        val profileGroups = bundle.groups.flatMap { group -> group.profileIds.map { it to group.id } }.toMap()
        val layouts = bundle.profiles.associate { it.id to it.canvasLayout } +
            bundle.groups.associate { groupOwner(it.id) to it.canvasLayout }
        return saved.copy(
            groups = bundle.groups.map { StoredGroup(it.id, it.name, it.autoSwap, it.canvasLayout) },
            profiles = bundle.profiles.map { profile ->
                StoredProfile(
                    id = profile.id, name = profile.name, source = profile.source,
                    outbounds = profile.outbounds.map { StoredOutbound(it.id, it.tag, it.type, it.singBoxJson) },
                    selectedOutboundId = profile.selectedOutboundId, dnsJson = profile.dnsJson,
                    dnsPolicy = profile.dnsPolicy, modeOverride = profile.modeOverride,
                    groupId = profileGroups[profile.id], subscriptionUrl = profile.subscriptionUrl,
                    canvasLayout = profile.canvasLayout,
                )
            },
            rules = bundle.rules.map { rule ->
                StoredRule(
                    id = rule.id, profileId = rule.ownerId, parentId = rule.parentId, enabled = rule.enabled,
                    sortIndex = rule.sortIndex, action = rule.action.uppercase(), pipeName = rule.pipeName,
                    title = rule.title, join = rule.join, apps = rule.apps, processes = rule.processes,
                    domains = rule.domains, domainSuffixes = rule.domainSuffixes, cidrs = rule.ipCidrs,
                    countries = rule.geoip, blocksJson = rule.blocksJson,
                )
            },
            rulePositions = bundle.rules.mapNotNull { rule ->
                (rule.position ?: TransferCodec.pointInLayout(layouts[rule.ownerId], rule.id))
                    ?.let { rule.id to RulePosition(it.x, it.y) }
            }.toMap(),
            selectedProfileId = bundle.selectedProfileId ?: saved.selectedProfileId?.takeIf { id -> bundle.profiles.any { it.id == id } }
                ?: bundle.profiles.firstOrNull()?.id,
        )
    }

    fun export(saved: StoredState, groupId: String? = null): String {
        val groups = if (groupId == null) saved.groups else listOf(saved.groups.firstOrNull { it.id == groupId }
            ?: error("Папка не найдена"))
        val profiles = if (groupId == null) saved.profiles else saved.profiles.filter { it.groupId == groupId }
        val profileIds = profiles.mapTo(HashSet()) { it.id }
        val owners = profileIds + groups.map { groupOwner(it.id) }.toSet()
        val rules = saved.rules.filter { it.profileId in owners }
        val layouts = profiles.associate { it.id to it.canvasLayout } + groups.associate { groupOwner(it.id) to it.canvasLayout }
        return TransferCodec.encode(TransferBundle(
            scope = if (groupId == null) "all" else "group",
            groups = groups.map { group -> TransferGroup(group.id, group.name,
                profiles.filter { it.groupId == group.id }.map { it.id }, group.autoSwap, group.canvasLayout) },
            profiles = profiles.map { profile -> TransferProfile(
                id = profile.id, name = profile.name, source = profile.source,
                outbounds = profile.outbounds.map { TransferOutbound(it.id, it.tag, it.type, it.singBoxJson) },
                selectedOutboundId = profile.selectedOutboundId, dnsJson = profile.dnsJson,
                dnsPolicy = profile.dnsPolicy, modeOverride = profile.modeOverride,
                subscriptionUrl = profile.subscriptionUrl, canvasLayout = profile.canvasLayout,
            ) },
            rules = rules.map { rule -> TransferRule(
                id = rule.id, ownerId = rule.profileId, parentId = rule.parentId,
                enabled = rule.enabled, sortIndex = rule.sortIndex, action = rule.action,
                pipeName = rule.pipeName, title = rule.title, join = rule.join,
                apps = rule.apps, processes = rule.processes, domains = rule.domains,
                domainSuffixes = rule.domainSuffixes, ipCidrs = rule.cidrs, geoip = rule.countries,
                blocksJson = rule.blocksJson,
                position = saved.rulePositions[rule.id]?.let { TransferPoint(it.x, it.y) }
                    ?: TransferCodec.pointInLayout(layouts[rule.profileId], rule.id),
            ) },
            selectedProfileId = saved.selectedProfileId?.takeIf { it in profileIds },
        ))
    }

    fun merge(saved: StoredState, raw: String): StoredState {
        val bundle = TransferCodec.decode(raw)
        val groupIds = bundle.groups.associate { it.id to newId() }
        val profileIds = bundle.profiles.associate { it.id to newId() }
        val ruleIds = bundle.rules.associate { it.id to newId() }
        val ownerIds = TransferCodec.remapOwnerIds(profileIds, groupIds)
        val layouts = bundle.profiles.associate { it.id to it.canvasLayout } +
            bundle.groups.associate { groupOwner(it.id) to it.canvasLayout }
        val importedGroups = bundle.groups.map { group ->
            StoredGroup(groupIds.getValue(group.id), group.name, group.autoSwap,
                TransferCodec.remapCanvasLayout(group.canvasLayout, ruleIds))
        }
        val profileToGroup = bundle.groups.flatMap { group -> group.profileIds.map { it to groupIds.getValue(group.id) } }.toMap()
        val profileById = bundle.profiles.associateBy { it.id }
        val orderedProfiles = bundle.profiles.filter { it.id !in profileToGroup } +
            bundle.groups.flatMap { group -> group.profileIds.map(profileById::getValue) }
        val importedProfiles = orderedProfiles.map { profile ->
            val outboundIds = profile.outbounds.associate { it.id to newId() }
            StoredProfile(
                id = profileIds.getValue(profile.id), name = profile.name, source = profile.source,
                outbounds = profile.outbounds.map { StoredOutbound(outboundIds.getValue(it.id), it.tag, it.type, it.singBoxJson) },
                selectedOutboundId = outboundIds.getValue(profile.selectedOutboundId),
                dnsJson = profile.dnsJson, dnsPolicy = profile.dnsPolicy,
                modeOverride = profile.modeOverride, groupId = profileToGroup[profile.id],
                subscriptionUrl = profile.subscriptionUrl,
                canvasLayout = TransferCodec.remapCanvasLayout(profile.canvasLayout, ruleIds),
            )
        }
        val importedRules = bundle.rules.map { rule ->
            StoredRule(
                id = ruleIds.getValue(rule.id),
                profileId = ownerIds.getValue(rule.ownerId),
                parentId = TransferCodec.remapParentId(rule.parentId, ruleIds),
                enabled = rule.enabled, sortIndex = rule.sortIndex, action = rule.action.uppercase(),
                pipeName = rule.pipeName, title = rule.title, join = rule.join,
                apps = rule.apps, processes = rule.processes, domains = rule.domains,
                domainSuffixes = rule.domainSuffixes, cidrs = rule.ipCidrs, countries = rule.geoip,
                blocksJson = rule.blocksJson,
            )
        }
        val positions = bundle.rules.mapNotNull { rule ->
            (rule.position ?: TransferCodec.pointInLayout(layouts[rule.ownerId], rule.id))
                ?.let { ruleIds.getValue(rule.id) to RulePosition(it.x, it.y) }
        }.toMap()
        return saved.copy(
            groups = saved.groups + importedGroups,
            profiles = saved.profiles + importedProfiles,
            rules = saved.rules + importedRules,
            rulePositions = saved.rulePositions + positions,
            selectedProfileId = saved.selectedProfileId ?: bundle.selectedProfileId?.let(profileIds::getValue)
                ?: importedProfiles.firstOrNull()?.id,
        )
    }
}
