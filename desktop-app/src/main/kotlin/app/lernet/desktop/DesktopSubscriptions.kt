package app.lernet.desktop

import app.lernet.config.parse.ImportedProfileDraft
import app.lernet.config.subscription.FolderView
import app.lernet.config.subscription.RefreshPlan
import app.lernet.config.subscription.SubscribedConfig
import app.lernet.config.subscription.SubscriptionFolders
import app.lernet.config.subscription.SubscriptionOrigin
import app.lernet.config.subscription.SubscriptionRefresh
import java.util.UUID

/** Windows side of a subscription refresh: the same planner as Android, applied to state.json. */
internal object DesktopSubscriptions {
    fun configs(saved: StoredState, url: String): List<SubscribedConfig> =
        saved.profiles.filter { it.subscriptionUrl == url }.map { profile ->
            SubscribedConfig(
                id = profile.id,
                name = profile.name,
                outbounds = profile.outbounds.map { it.model() },
                selectedOutboundId = profile.selectedOutboundId,
            )
        }

    /** Put every subscription that still has configs into its own folder. Safe to call more than once. */
    fun adoptAll(saved: StoredState): StoredState {
        val urls = saved.profiles.mapNotNull { it.subscriptionUrl?.takeIf(String::isNotBlank) }.distinct()
        return urls.fold(saved) { state, url -> place(state, url) }
    }

    /**
     * First import of [url], or a refresh when configs of that address already exist.
     * [addedIds] are the new config ids, in plan order (every id on a first import).
     */
    fun install(
        saved: StoredState,
        drafts: List<ImportedProfileDraft>,
        url: String,
        origins: Map<String, SubscriptionOrigin>,
    ): Installed {
        val existing = configs(saved, url)
        if (existing.isEmpty()) {
            val fresh = drafts.map {
                StoredProfile.fromDraft(it.copy(subscriptionUrl = url)).copy(dnsPolicy = saved.defaultDnsPolicy)
            }
            val withProfiles = saved.copy(
                profiles = saved.profiles + fresh,
                selectedProfileId = saved.selectedProfileId ?: fresh.firstOrNull()?.id,
            )
            return Installed(place(withProfiles, url), fresh.map { it.id }, refreshed = false, plan = null)
        }
        val plan = SubscriptionRefresh.plan(existing, drafts, origins)
        val (next, added) = apply(saved, url, plan)
        return Installed(next, added, refreshed = true, plan = plan)
    }

    /** Returns the new state and the ids of added configs, in plan order. The folder matches the remote list. */
    fun apply(saved: StoredState, url: String, plan: RefreshPlan): Pair<StoredState, List<String>> {
        val updates = plan.updates.associateBy { it.id }
        val added = plan.adds.map { draft ->
            StoredProfile.fromDraft(draft.copy(subscriptionUrl = url)).copy(dnsPolicy = saved.defaultDnsPolicy)
        }
        val removed = plan.removes.toSet()
        val removedRules = saved.rules.filter { it.profileId in removed }.mapTo(HashSet()) { it.id }
        val profiles = saved.profiles.filterNot { it.id in removed }.map { profile ->
            val update = updates[profile.id] ?: return@map profile
            profile.copy(
                name = update.name ?: profile.name,
                outbounds = update.outbounds.map { StoredOutbound(it.id, it.tag, it.type, it.singBoxJson) },
                selectedOutboundId = update.selectedOutboundId,
            )
        } + added
        val selected = when {
            saved.selectedProfileId !in removed -> saved.selectedProfileId
            else -> profiles.firstOrNull { it.subscriptionUrl == url }?.id ?: profiles.firstOrNull()?.id
        }
        val next = saved.copy(
            profiles = profiles,
            rules = saved.rules.filterNot { it.profileId in removed },
            rulePositions = saved.rulePositions.filterKeys { it !in removedRules },
            selectedProfileId = selected,
        )
        return place(next, url) to added.map { it.id }
    }

    /** Folder membership becomes exactly the profiles that carry [url]. */
    fun place(saved: StoredState, url: String): StoredState {
        val ids = saved.profiles.filter { it.subscriptionUrl == url }.map { it.id }
        val views = saved.groups.map { group ->
            FolderView(group.id, group.name, saved.profiles.filter { it.groupId == group.id }.map { it.id }, group.subscriptionUrl)
        }
        if (ids.isEmpty()) {
            val marked = views.firstOrNull { it.subscriptionUrl == url } ?: return saved
            return saved.copy(profiles = saved.profiles.map { if (it.groupId == marked.id) it.copy(groupId = null) else it })
        }
        val plan = SubscriptionFolders.adopt(url, ids, views)
        val folderId = plan.folderId ?: UUID.randomUUID().toString()
        val groups = if (plan.create) {
            saved.groups + StoredGroup(folderId, plan.name, subscriptionUrl = url)
        } else {
            saved.groups.map { if (it.id == folderId) it.copy(subscriptionUrl = url) else it }
        }
        val members = plan.memberIds.toSet()
        val profiles = saved.profiles.map { profile ->
            when {
                profile.id in members -> profile.copy(groupId = folderId)
                profile.groupId == folderId -> profile.copy(groupId = null)
                else -> profile
            }
        }
        return saved.copy(groups = groups, profiles = profiles)
    }

    data class Installed(
        val state: StoredState,
        val addedIds: List<String>,
        val refreshed: Boolean,
        val plan: RefreshPlan?,
    )
}
