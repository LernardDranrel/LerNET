package app.lernet.engine.policy

object GroupFailoverPolicy {
    fun isEligible(
        settings: FailoverSettings,
        group: ManualFailoverGroup?,
        currentOutboundId: String?,
    ): Boolean {
        if (!settings.enabled) return false
        if (group == null) return false
        if (settings.groupId != null && settings.groupId != group.id) return false
        val others = candidates(group, currentOutboundId, emptySet())
        return others.isNotEmpty()
    }

    fun candidates(
        group: ManualFailoverGroup,
        currentOutboundId: String?,
        knownDead: Set<String>,
    ): List<String> =
        group.outboundIds
            .filter { it != currentOutboundId }
            .filterNot { it in knownDead }

    fun firstAlive(probeOrder: List<String>, alive: Set<String>): String? =
        probeOrder.firstOrNull { it in alive }
}
