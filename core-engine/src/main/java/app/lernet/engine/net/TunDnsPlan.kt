package app.lernet.engine.net

data class PlannedDns(
    val servers: List<String>,
    val notes: List<String>,
)

/**
 * SFA copies `TunOptions.dnsServerAddress` when auto_route && dnsMode != disabled.
 * 1.14: if unset, engine derives the next IP after the first TUN address
 * (`172.19.0.1/30` → `172.19.0.2`). Do not invent `1.1.1.1` as VpnService DNS —
 * that fights the hijack stub.
 */
object TunDnsPlan {
    fun isIpv6Literal(value: String): Boolean = value.contains(':')

    fun plan(
        fromLibbox: List<String>,
        hasIpv6TunAddress: Boolean,
        derivedFromTun: String? = null,
        dnsMode: String = "hijack",
    ): PlannedDns {
        val notes = mutableListOf<String>()
        if (dnsMode == TunRoutePlan.DNS_MODE_DISABLED) {
            notes += "dnsMode=disabled — SFA adds no Builder DNS"
            return PlannedDns(emptyList(), notes)
        }
        val servers = fromLibbox.map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        if (!hasIpv6TunAddress) {
            val dropped = servers.filter { isIpv6Literal(it) }
            servers.removeAll { isIpv6Literal(it) }
            if (dropped.isNotEmpty()) {
                notes += "dropped IPv6 DNS ${dropped.joinToString()} — no IPv6 TUN address"
            }
        }
        if (servers.isEmpty()) {
            val derived = derivedFromTun?.takeIf { it.isNotBlank() }
            if (derived != null) {
                servers += derived
                notes += "derived Builder DNS $derived from TUN address (1.14 dns_address default)"
            } else {
                notes += "TunOptions dnsServerAddress empty and no TUN IPv4 to derive"
            }
        }
        return PlannedDns(servers.distinct(), notes)
    }
}
