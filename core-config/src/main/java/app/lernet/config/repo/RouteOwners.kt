package app.lernet.config.repo

/** Route trees are keyed by profile id or by a group owner id (`grp_<groupId>`). */
object RouteOwners {
    private const val GROUP_PREFIX = "grp_"

    fun group(groupId: String): String = "$GROUP_PREFIX$groupId"

    fun isGroup(ownerId: String): Boolean = ownerId.startsWith(GROUP_PREFIX)

    fun groupIdOf(ownerId: String): String? =
        if (isGroup(ownerId)) ownerId.removePrefix(GROUP_PREFIX).takeIf { it.isNotEmpty() } else null
}
