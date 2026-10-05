package app.lernet.config.policy

import app.lernet.config.transfer.TransferBundle

/** Additions may extend a live folder; replacing existing credentials or routes always needs explicit application. */
object PolicyInventoryChange {
    fun isAddition(before: TransferBundle?, after: TransferBundle): Boolean {
        if (before == null) return false
        val profiles = after.profiles.associateBy { it.id }
        val previousProfiles = before.profiles.associateBy { it.id }
        if (before.profiles.any { profiles[it.id] != it }) return false
        val groups = after.groups.associateBy { it.id }
        val previousGroups = before.groups.associateBy { it.id }
        if (before.groups.any { previous ->
                val current = groups[previous.id] ?: return@any true
                current.copy(profileIds = previous.profileIds) != previous ||
                    current.profileIds.take(previous.profileIds.size) != previous.profileIds
            }
        ) {
            return false
        }
        val rules = after.rules.associateBy { it.id }
        val previousRules = before.rules.associateBy { it.id }
        if (before.rules.any { rules[it.id] != it }) return false
        val oldOwners = before.profiles.map { it.id }.toSet() + before.groups.map { "grp_${it.id}" }
        if (after.rules.any { it.ownerId in oldOwners && it.id !in previousRules }) return false
        return after.profiles.any { it.id !in previousProfiles } ||
            after.groups.any { group ->
                val previousMembers = previousGroups[group.id]?.profileIds?.toSet() ?: return@any true
                group.profileIds.any { it !in previousMembers }
            }
    }
}
