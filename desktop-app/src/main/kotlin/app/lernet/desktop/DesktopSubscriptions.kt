package app.lernet.desktop

import app.lernet.config.subscription.RefreshPlan
import app.lernet.config.subscription.SubscribedConfig

/** Windows side of a subscription refresh: the same planner as Android, applied to state.json. */
internal object DesktopSubscriptions {
    fun configs(saved: StoredState, url: String): List<SubscribedConfig> =
        saved.profiles.filter { it.subscriptionUrl == url }.map { profile ->
            SubscribedConfig(
                id = profile.id,
                name = profile.name,
                outbounds = profile.outbounds.map { it.model() },
                selectedOutboundId = profile.selectedOutboundId,
                selected = profile.id == saved.selectedProfileId,
                hasOwnSettings = profile.modeOverride != null || profile.canvasLayout != null ||
                    saved.rules.any { it.profileId == profile.id },
            )
        }

    /** Returns the new state and the ids of added configs, in plan order. New configs join the subscription's folder. */
    fun apply(saved: StoredState, url: String, plan: RefreshPlan): Pair<StoredState, List<String>> {
        val updates = plan.updates.associateBy { it.id }
        val folder = saved.profiles.filter { it.subscriptionUrl == url && it.groupId != null }
            .groupingBy { it.groupId!! }.eachCount().maxByOrNull { it.value }?.key
            ?.takeIf { id -> saved.groups.any { it.id == id } }
        val added = plan.adds.map { draft ->
            StoredProfile.fromDraft(draft.copy(subscriptionUrl = url)).copy(groupId = folder, dnsPolicy = saved.defaultDnsPolicy)
        }
        val removed = plan.removes.toSet() - setOfNotNull(saved.selectedProfileId)
        val removedRules = saved.rules.filter { it.profileId in removed }.mapTo(HashSet()) { it.id }
        val profiles = saved.profiles.filterNot { it.id in removed }.map { profile ->
            val update = updates[profile.id] ?: return@map profile
            profile.copy(
                name = update.name ?: profile.name,
                outbounds = update.outbounds.map { StoredOutbound(it.id, it.tag, it.type, it.singBoxJson) },
                selectedOutboundId = update.selectedOutboundId,
            )
        } + added
        return saved.copy(
            profiles = profiles,
            rules = saved.rules.filterNot { it.profileId in removed },
            rulePositions = saved.rulePositions.filterKeys { it !in removedRules },
        ) to added.map { it.id }
    }
}
