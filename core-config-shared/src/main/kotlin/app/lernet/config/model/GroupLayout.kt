package app.lernet.config.model

object GroupLayout {
    fun moveProfile(
        groups: List<Group>,
        profileId: String,
        targetGroupId: String,
        targetIndex: Int,
    ): List<Group> {
        val stripped = groups.map { group ->
            group.copy(profileIds = group.profileIds.filterNot { it == profileId })
        }
        return stripped.map { group ->
            if (group.id != targetGroupId) {
                group
            } else {
                val ids = group.profileIds.toMutableList()
                ids.add(targetIndex.coerceIn(0, ids.size), profileId)
                group.copy(profileIds = ids)
            }
        }
    }

    fun moveGroup(groups: List<Group>, groupId: String, toIndex: Int): List<Group> {
        val current = groups.indexOfFirst { it.id == groupId }
        if (current < 0) return groups
        val next = groups.toMutableList()
        val item = next.removeAt(current)
        next.add(toIndex.coerceIn(0, next.size), item)
        return next
    }
}
