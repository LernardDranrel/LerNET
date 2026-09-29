package app.lernet.config.repo

import app.lernet.config.db.GroupDao
import app.lernet.config.db.LerNetDatabase
import app.lernet.config.db.GroupEntity
import app.lernet.config.db.GroupMemberDao
import app.lernet.config.db.GroupMemberEntity
import app.lernet.config.db.OutboundDao
import app.lernet.config.db.OutboundEntity
import app.lernet.config.db.ProfileDao
import app.lernet.config.db.ProfileEntity
import app.lernet.config.db.RuleNodeDao
import app.lernet.config.db.RuleNodeEntity
import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.Group
import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.Profile
import app.lernet.config.model.ProfileSource
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.config.transfer.TransferRule
import app.lernet.config.parse.ImportedProfileDraft
import app.lernet.config.parse.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class RuleNodeRecord(
    val id: String,
    val profileId: String,
    val parentId: String?,
    val enabled: Boolean,
    val sortIndex: Int,
    val action: String,
    val apps: List<String>,
    val domains: List<String>,
    val domainSuffixes: List<String>,
    val ipCidrs: List<String>,
    val geoip: List<String>,
    val pipeName: String = "",
    val blocksJson: String = "",
    val title: String = "",
    val processes: List<String> = emptyList(),
)

class ConfigRepository(
    private val profileDao: ProfileDao,
    private val outboundDao: OutboundDao,
    private val ruleNodeDao: RuleNodeDao,
    private val groupDao: GroupDao,
    private val groupMemberDao: GroupMemberDao,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val database: LerNetDatabase? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<Profile>> =
        combine(profileDao.observeProfiles(), outboundDao.observeAll()) { profiles, outbounds ->
            val byProfile = outbounds.groupBy { it.profileId }
            profiles.map { it.toModel(byProfile[it.id].orEmpty()) }
        }

    val groups: Flow<List<Group>> =
        combine(
            groupDao.observeGroups(),
            groupMemberDao.observeMembers(),
            ruleNodeDao.observeAll(),
        ) { groups, members, nodes ->
            val byGroup = members.groupBy { it.groupId }
            val routeOwners = nodes.map { it.profileId }.toSet()
            groups.map { group ->
                val ownerId = RouteOwners.group(group.id)
                Group(
                    id = group.id,
                    name = group.name,
                    profileIds = byGroup[group.id].orEmpty().sortedBy { it.sortIndex }.map { it.profileId },
                    canvasLayout = group.canvasLayout,
                    hasRoutes = ownerId in routeOwners,
                    autoFailover = group.autoFailover,
                )
            }
        }

    val ruleNodes: Flow<List<RuleNodeRecord>> = ruleNodeDao.observeAll().combine(profileDao.observeProfiles()) { nodes, _ ->
        nodes.map { it.toRecord() }
    }

    suspend fun exportTransfer(groupId: String? = null, selectedProfileId: String? = null): String {
        val allProfiles = profiles.first()
        val allGroups = groups.first()
        val chosenGroups = if (groupId == null) allGroups else listOf(allGroups.firstOrNull { it.id == groupId }
            ?: error("Папка не найдена"))
        val chosenProfiles = if (groupId == null) allProfiles else allProfiles.filter { it.id in chosenGroups.single().profileIds }
        val ids = chosenProfiles.mapTo(HashSet()) { it.id }
        val owners = ids + chosenGroups.map { RouteOwners.group(it.id) }.toSet()
        val chosenRules = ruleNodes.first().filter { it.profileId in owners }
        val layouts = chosenProfiles.associate { it.id to it.canvasLayout } +
            chosenGroups.associate { RouteOwners.group(it.id) to it.canvasLayout }
        return TransferCodec.encode(TransferBundle(
            scope = if (groupId == null) "all" else "group",
            groups = chosenGroups.map { TransferGroup(it.id, it.name, it.profileIds.filter(ids::contains), it.autoFailover, it.canvasLayout) },
            profiles = chosenProfiles.map { profile -> TransferProfile(
                id = profile.id, name = profile.name, source = profile.source.name,
                outbounds = profile.outbounds.map { TransferOutbound(it.id, it.tag, it.type, it.singBoxJson) },
                selectedOutboundId = profile.selectedOutboundId, dnsJson = profile.dnsJson,
                dnsPolicy = profile.dnsPolicy.name, subscriptionUrl = profile.subscriptionUrl,
                canvasLayout = profile.canvasLayout,
                modeOverride = profile.modeOverride,
            ) },
            rules = chosenRules.map { rule -> TransferRule(
                id = rule.id, ownerId = rule.profileId, parentId = rule.parentId,
                enabled = rule.enabled, sortIndex = rule.sortIndex, action = rule.action,
                pipeName = rule.pipeName, title = rule.title, apps = rule.apps,
                processes = rule.processes,
                domains = rule.domains, domainSuffixes = rule.domainSuffixes,
                ipCidrs = rule.ipCidrs, geoip = rule.geoip, blocksJson = rule.blocksJson,
                position = TransferCodec.pointInLayout(layouts[rule.profileId], rule.id),
            ) },
            selectedProfileId = selectedProfileId?.takeIf(ids::contains),
        ))
    }

    /** Validates first, then imports a copy in one Room transaction. Existing data is untouched. */
    suspend fun importTransfer(raw: String, defaultDnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY): TransferBundle {
        val bundle = TransferCodec.decode(raw)
        val operation: suspend () -> Unit = {
            val previousOrder = profileDao.listProfiles().map { it.id }
            val groupIds = bundle.groups.associate { it.id to newId() }
            val profileIds = bundle.profiles.associate { it.id to newId() }
            val ruleIds = bundle.rules.associate { it.id to newId() }
            val ownerIds = TransferCodec.remapOwnerIds(profileIds, groupIds)
            bundle.profiles.forEach { item ->
                val outboundIds = item.outbounds.associate { it.id to newId() }
                restoreProfile(Profile(
                    id = profileIds.getValue(item.id), name = item.name,
                    createdAtEpochMs = nowMs(), updatedAtEpochMs = nowMs(),
                    source = ProfileSource.entries.firstOrNull { it.name == item.source } ?: ProfileSource.JSON_PASTE,
                    selectedOutboundId = outboundIds.getValue(item.selectedOutboundId),
                    outbounds = item.outbounds.map { NormalizedOutbound(outboundIds.getValue(it.id), it.tag, it.type, it.singBoxJson) },
                    subscriptionUrl = item.subscriptionUrl, lastRefreshEpochMs = null,
                    dnsJson = item.dnsJson,
                    dnsPolicy = if (item.dnsPolicy == "SYSTEM") defaultDnsPolicy else DnsPolicy.fromStorage(item.dnsPolicy),
                    canvasLayout = TransferCodec.remapCanvasLayout(item.canvasLayout, ruleIds)
                        ?: TransferCodec.layoutFromPositions(bundle.rules.filter { it.ownerId == item.id }, ruleIds),
                    modeOverride = item.modeOverride,
                ), emptyList())
            }
            bundle.groups.forEach { item ->
                val id = groupIds.getValue(item.id)
                upsertGroup(item.name, id)
                setGroupAutoFailover(id, item.autoSwap)
                setGroupMembers(id, item.profileIds.map(profileIds::getValue))
                (TransferCodec.remapCanvasLayout(item.canvasLayout, ruleIds)
                    ?: TransferCodec.layoutFromPositions(bundle.rules.filter { it.ownerId == RouteOwners.group(item.id) }, ruleIds))
                    ?.let { writeCanvasLayout(RouteOwners.group(id), it) }
            }
            val remappedRules = bundle.rules.map { rule ->
                RuleNodeRecord(
                    id = ruleIds.getValue(rule.id),
                    profileId = ownerIds.getValue(rule.ownerId),
                    parentId = TransferCodec.remapParentId(rule.parentId, ruleIds), enabled = rule.enabled,
                    sortIndex = rule.sortIndex, action = rule.action.lowercase(),
                    apps = rule.apps, domains = rule.domains, domainSuffixes = rule.domainSuffixes,
                    processes = rule.processes,
                    ipCidrs = rule.ipCidrs, geoip = rule.geoip, pipeName = rule.pipeName,
                    blocksJson = rule.androidConditionsJson(), title = rule.title,
                )
            }
            remappedRules.groupBy { it.profileId }.forEach { (owner, nodes) -> replaceRuleNodes(owner, nodes) }
            bundle.profiles.forEach { item ->
                val id = profileIds.getValue(item.id)
                if (remappedRules.none { it.profileId == id }) ensureDefaultElse(id)
            }
            reorderProfiles(previousOrder + bundle.profiles.map { profileIds.getValue(it.id) })
        }
        if (database != null) database.withTransaction { operation() } else operation()
        return bundle
    }

    suspend fun importDrafts(drafts: List<ImportedProfileDraft>, defaultDnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY): List<String> {
        val ids = mutableListOf<String>()
        drafts.forEach { draft ->
            ids += insertDraft(draft, defaultDnsPolicy)
        }
        return ids
    }

    suspend fun insertDraft(draft: ImportedProfileDraft, defaultDnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY): String {
        val id = newId()
        val now = nowMs()
        profileDao.upsertProfile(
            ProfileEntity(
                id = id,
                name = draft.name,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                source = draft.source.name,
                selectedOutboundId = draft.selectedOutboundId,
                subscriptionUrl = draft.subscriptionUrl,
                lastRefreshEpochMs = if (draft.subscriptionUrl != null) now else null,
                dnsJson = draft.dnsJson,
                dnsPolicy = defaultDnsPolicy.name,
            ),
        )
        outboundDao.upsertAll(draft.outbounds.map { it.toEntity(id) })
        ruleNodeDao.upsertAll(listOf(defaultCatchAll(id)))
        return id
    }

    suspend fun getProfile(id: String): Profile? {
        val entity = profileDao.getProfile(id) ?: return null
        return entity.toModel(outboundDao.listForProfile(id))
    }

    suspend fun selectOutbound(profileId: String, outboundId: String) {
        val profile = profileDao.getProfile(profileId) ?: return
        profileDao.updateProfile(profile.copy(selectedOutboundId = outboundId, updatedAtEpochMs = nowMs()))
    }

    suspend fun renameProfile(profileId: String, name: String) {
        val profile = profileDao.getProfile(profileId) ?: return
        profileDao.updateProfile(profile.copy(name = name, updatedAtEpochMs = nowMs()))
    }

    suspend fun setModeOverride(profileId: String, mode: String?) {
        require(mode == null || mode == "FULL_VPN" || mode == "PROXY")
        val profile = profileDao.getProfile(profileId) ?: return
        profileDao.updateProfile(profile.copy(modeOverride = mode, updatedAtEpochMs = nowMs()))
    }

    suspend fun reorderProfiles(orderedIds: List<String>) {
        val rows = profileDao.listProfiles().associateBy { it.id }
        profileDao.updateProfileOrder(orderedIds.mapIndexedNotNull { index, id ->
            rows[id]?.copy(sortIndex = index + 1)
        })
    }

    suspend fun restoreProfile(profile: Profile, nodes: List<RuleNodeRecord>) {
        profileDao.upsertProfile(
            ProfileEntity(
                id = profile.id,
                name = profile.name,
                createdAtEpochMs = profile.createdAtEpochMs,
                updatedAtEpochMs = nowMs(),
                source = profile.source.name,
                selectedOutboundId = profile.selectedOutboundId,
                subscriptionUrl = profile.subscriptionUrl,
                lastRefreshEpochMs = profile.lastRefreshEpochMs,
                dnsJson = profile.dnsJson,
                dnsPolicy = profile.dnsPolicy.name,
                canvasLayout = profile.canvasLayout,
                modeOverride = profile.modeOverride,
            ),
        )
        outboundDao.upsertAll(profile.outbounds.map { it.toEntity(profile.id) })
        if (nodes.isNotEmpty()) {
            ruleNodeDao.upsertAll(nodes.map { it.toEntity() })
        }
    }

    suspend fun deleteProfile(profileId: String) {
        outboundDao.deleteForProfile(profileId)
        ruleNodeDao.deleteForProfile(profileId)
        groupMemberDao.deleteForProfile(profileId)
        profileDao.deleteProfile(profileId)
    }

    suspend fun duplicateProfile(profileId: String): String? {
        val profile = getProfile(profileId) ?: return null
        val nodes = ruleNodeDao.listForProfile(profileId)
        val newProfileId = newId()
        val now = nowMs()
        val outboundIdMap = profile.outbounds.associate { it.id to newId() }
        val nodeIdMap = nodes.associate { it.id to newId() }
        val selected = outboundIdMap[profile.selectedOutboundId] ?: outboundIdMap.values.first()
        profileDao.upsertProfile(
            ProfileEntity(
                id = newProfileId,
                name = "${profile.name} (копия)",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                source = profile.source.name,
                selectedOutboundId = selected,
                subscriptionUrl = profile.subscriptionUrl,
                lastRefreshEpochMs = profile.lastRefreshEpochMs,
                dnsJson = profile.dnsJson,
                dnsPolicy = profile.dnsPolicy.name,
                modeOverride = profile.modeOverride,
                canvasLayout = TransferCodec.remapCanvasLayout(profile.canvasLayout, nodeIdMap),
            ),
        )
        outboundDao.upsertAll(
            profile.outbounds.map { outbound ->
                outbound.copy(id = outboundIdMap.getValue(outbound.id)).toEntity(newProfileId)
            },
        )
        ruleNodeDao.upsertAll(
            nodes.map { node ->
                node.copy(
                    id = nodeIdMap.getValue(node.id),
                    profileId = newProfileId,
                    parentId = TransferCodec.remapParentId(node.parentId, nodeIdMap),
                )
            },
        )
        return newProfileId
    }

    suspend fun listRuleNodes(ownerId: String): List<RuleNodeRecord> =
        ruleNodeDao.listForProfile(ownerId).map { it.toRecord() }

    suspend fun writeCanvasLayout(ownerId: String, json: String) {
        val groupId = RouteOwners.groupIdOf(ownerId)
        if (groupId != null) {
            val group = groupDao.listGroups().firstOrNull { it.id == groupId } ?: return
            groupDao.upsertGroup(group.copy(canvasLayout = json))
            return
        }
        val profile = profileDao.getProfile(ownerId) ?: return
        profileDao.updateProfile(profile.copy(canvasLayout = json))
    }

    suspend fun replaceRuleNodes(ownerId: String, nodes: List<RuleNodeRecord>) {
        ruleNodeDao.deleteForProfile(ownerId)
        val stamped = nodes.map { it.copy(profileId = ownerId) }
        ruleNodeDao.upsertAll(stamped.map { it.toEntity() })
        val groupId = RouteOwners.groupIdOf(ownerId)
        if (groupId != null) return
        val profile = profileDao.getProfile(ownerId) ?: return
        profileDao.updateProfile(profile.copy(updatedAtEpochMs = nowMs()))
    }

    /** A folder owns routing for all its profiles, including before custom rules are added. */
    suspend fun routingOwnerForProfile(profileId: String): Pair<String, Group?> {
        val groups = groupDao.listGroups()
        for (group in groups.sortedBy { it.sortIndex }) {
            val profileIds = groupMemberDao.listForGroup(group.id).sortedBy { it.sortIndex }.map { it.profileId }
            if (profileId !in profileIds) continue
            val ownerId = RouteOwners.group(group.id)
            return ownerId to Group(
                id = group.id,
                name = group.name,
                profileIds = profileIds,
                canvasLayout = group.canvasLayout,
                hasRoutes = true,
                autoFailover = group.autoFailover,
            )
        }
        return profileId to null
    }

    suspend fun ensureOwnerRoutes(ownerId: String): List<RuleNodeRecord> = ensureDefaultElse(ownerId)

    /** New tree: one root «Иначе» on the auto pipe. Existing trees are left alone. */
    suspend fun ensureDefaultElse(ownerId: String): List<RuleNodeRecord> {
        val existing = listRuleNodes(ownerId)
        if (existing.isNotEmpty()) return existing
        val seed = defaultCatchAll(ownerId)
        ruleNodeDao.upsertAll(listOf(seed))
        return listOf(seed.toRecord())
    }

    suspend fun readCanvasLayout(ownerId: String): String? {
        val groupId = RouteOwners.groupIdOf(ownerId)
        if (groupId != null) {
            return groupDao.listGroups().firstOrNull { it.id == groupId }?.canvasLayout
        }
        return profileDao.getProfile(ownerId)?.canvasLayout
    }

    suspend fun ownerDisplayName(ownerId: String): String {
        val groupId = RouteOwners.groupIdOf(ownerId)
        if (groupId != null) {
            return groupDao.listGroups().firstOrNull { it.id == groupId }?.name.orEmpty()
        }
        return profileDao.getProfile(ownerId)?.name.orEmpty()
    }

    suspend fun upsertGroup(name: String, id: String = newId()): String {
        val next = (groupDao.listGroups().maxOfOrNull { it.sortIndex } ?: -1) + 1
        groupDao.upsertGroup(GroupEntity(id = id, name = name.trim(), sortIndex = next))
        return id
    }

    suspend fun renameGroup(id: String, name: String) {
        val existing = groupDao.listGroups().firstOrNull { it.id == id } ?: return
        groupDao.upsertGroup(existing.copy(name = name.trim()))
    }

    suspend fun setGroupAutoFailover(id: String, enabled: Boolean) {
        val existing = groupDao.listGroups().firstOrNull { it.id == id } ?: return
        groupDao.upsertGroup(existing.copy(autoFailover = enabled))
    }

    suspend fun reorderGroups(orderedIds: List<String>) {
        val existing = groupDao.listGroups().associateBy { it.id }
        orderedIds.forEachIndexed { index, id ->
            val row = existing[id] ?: return@forEachIndexed
            groupDao.upsertGroup(row.copy(sortIndex = index))
        }
    }

    suspend fun replaceAllMemberships(groups: List<Group>) {
        val rows = groups.flatMap { group ->
            group.profileIds.mapIndexed { index, profileId ->
                GroupMemberEntity(groupId = group.id, profileId = profileId, sortIndex = index)
            }
        }
        groupMemberDao.replaceAllGroupMembers(groups.map { it.id }, rows)
    }

    suspend fun deleteGroup(id: String) {
        groupMemberDao.deleteForGroup(id)
        ruleNodeDao.deleteForProfile(RouteOwners.group(id))
        groupDao.deleteGroup(id)
    }

    suspend fun setGroupMembers(groupId: String, profileIds: List<String>) {
        profileIds.forEach { profileId -> groupMemberDao.deleteForProfile(profileId) }
        groupMemberDao.replaceMembers(
            groupId,
            profileIds.mapIndexed { index, profileId ->
                GroupMemberEntity(groupId = groupId, profileId = profileId, sortIndex = index)
            },
        )
    }

    private fun defaultCatchAll(profileId: String): RuleNodeEntity =
        RuleNodeEntity(
            id = newId(),
            profileId = profileId,
            parentId = null,
            enabled = true,
            sortIndex = 0,
            action = "proxy",
            appsJson = "[]",
            domainsJson = "[]",
            suffixesJson = "[]",
            cidrsJson = "[]",
            geoipJson = "[]",
        )

    private fun ProfileEntity.toModel(outbounds: List<OutboundEntity>): Profile =
        Profile(
            id = id,
            name = name,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
            source = ProfileSource.fromStorage(source),
            selectedOutboundId = selectedOutboundId,
            outbounds = outbounds.map {
                NormalizedOutbound(id = it.id, tag = it.tag, type = it.type, singBoxJson = it.singBoxJson)
            },
            subscriptionUrl = subscriptionUrl,
            lastRefreshEpochMs = lastRefreshEpochMs,
            dnsJson = dnsJson,
            dnsPolicy = DnsPolicy.fromStorage(dnsPolicy),
            canvasLayout = canvasLayout,
            modeOverride = modeOverride,
        )

    suspend fun saveEditor(
        profileId: String,
        outboundId: String,
        singBoxJson: String,
        dnsJson: String?,
        dnsPolicy: DnsPolicy,
    ) {
        val profile = profileDao.getProfile(profileId) ?: return
        val outbound = outboundDao.listForProfile(profileId).firstOrNull { it.id == outboundId } ?: return
        outboundDao.upsertAll(listOf(outbound.copy(singBoxJson = singBoxJson)))
        profileDao.updateProfile(
            profile.copy(
                dnsJson = dnsJson,
                dnsPolicy = dnsPolicy.name,
                updatedAtEpochMs = nowMs(),
            ),
        )
    }

    private fun NormalizedOutbound.toEntity(profileId: String): OutboundEntity =
        OutboundEntity(id = id, profileId = profileId, tag = tag, type = type, singBoxJson = singBoxJson)

    private fun RuleNodeEntity.toRecord(): RuleNodeRecord =
        RuleNodeRecord(
            id = id,
            profileId = profileId,
            parentId = parentId,
            enabled = enabled,
            sortIndex = sortIndex,
            action = action,
            apps = decodeList(appsJson),
            domains = decodeList(domainsJson),
            domainSuffixes = decodeList(suffixesJson),
            ipCidrs = decodeList(cidrsJson),
            geoip = decodeList(geoipJson),
            pipeName = pipeName,
            blocksJson = blocksJson,
            title = title,
            processes = decodeList(processesJson),
        )

    private fun RuleNodeRecord.toEntity(): RuleNodeEntity =
        RuleNodeEntity(
            id = id,
            profileId = profileId,
            parentId = parentId,
            enabled = enabled,
            sortIndex = sortIndex,
            action = action,
            appsJson = encodeList(apps),
            domainsJson = encodeList(domains),
            suffixesJson = encodeList(domainSuffixes),
            cidrsJson = encodeList(ipCidrs),
            geoipJson = encodeList(geoip),
            pipeName = pipeName,
            blocksJson = blocksJson,
            title = title,
            processesJson = encodeList(processes),
        )

    private fun decodeList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return element.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    private fun encodeList(values: List<String>): String =
        JsonArray(values.map { JsonPrimitive(it) }).toString()
}
