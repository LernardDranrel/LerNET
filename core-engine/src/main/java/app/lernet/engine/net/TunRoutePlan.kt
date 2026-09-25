package app.lernet.engine.net

data class CidrPrefix(
    val address: String,
    val prefix: Int,
) {
    fun label(): String = "$address/$prefix"

    fun isIpv6(): Boolean = address.contains(':')

    fun isIpv4(): Boolean = !isIpv6()
}

data class OpenTunInput(
    val autoRoute: Boolean,
    val api33: Boolean,
    val dnsMode: String,
    val inet4Address: List<CidrPrefix> = emptyList(),
    val inet6Address: List<CidrPrefix> = emptyList(),
    val inet4RouteAddress: List<CidrPrefix> = emptyList(),
    val inet6RouteAddress: List<CidrPrefix> = emptyList(),
    val inet4RouteRange: List<CidrPrefix> = emptyList(),
    val inet6RouteRange: List<CidrPrefix> = emptyList(),
    val inet4RouteExclude: List<CidrPrefix> = emptyList(),
    val inet6RouteExclude: List<CidrPrefix> = emptyList(),
    val dnsServers: List<String> = emptyList(),
)

data class PlannedTun(
    val addresses: List<CidrPrefix>,
    val routes: List<CidrPrefix>,
    val notes: List<String>,
    val excludes: List<CidrPrefix> = emptyList(),
    val dnsServers: List<String> = emptyList(),
)

/**
 * SFA `VPNService.openTun` route/DNS decisions (sing-box-for-android `main`).
 * Builder copies TunOptions iterators; it does not invent a parallel table.
 */
object TunRoutePlan {
    val IPV4_LOCAL = CidrPrefix("172.19.0.1", 30)
    val IPV6_ULA = CidrPrefix("fdfe:dcba:9876::1", 126)
    val IPV4_DEFAULT = CidrPrefix("0.0.0.0", 0)
    val IPV6_DEFAULT = CidrPrefix("::", 0)
    const val DNS_MODE_DISABLED = "disabled"

    fun plan(input: OpenTunInput): PlannedTun {
        val notes = mutableListOf<String>()
        val addresses = input.inet4Address + input.inet6Address
        if (!input.autoRoute) {
            notes += "autoRoute=false — not installing default routes (SFA same)"
            return PlannedTun(addresses, emptyList(), notes, dnsServers = emptyList())
        }
        val routes: List<CidrPrefix>
        val excludes: List<CidrPrefix>
        if (input.api33) {
            val v4 = if (input.inet4RouteAddress.isNotEmpty()) {
                input.inet4RouteAddress
            } else if (input.inet4Address.isNotEmpty()) {
                notes += "API33: no inet4RouteAddress, add 0.0.0.0/0 (SFA)"
                listOf(IPV4_DEFAULT)
            } else {
                emptyList()
            }
            val v6 = if (input.inet6RouteAddress.isNotEmpty()) {
                input.inet6RouteAddress
            } else if (input.inet6Address.isNotEmpty()) {
                notes += "API33: no inet6RouteAddress, add ::/0 (SFA)"
                listOf(IPV6_DEFAULT)
            } else {
                emptyList()
            }
            routes = v4 + v6
            excludes = input.inet4RouteExclude + input.inet6RouteExclude
        } else {
            routes = input.inet4RouteRange + input.inet6RouteRange
            excludes = emptyList()
        }
        val dns = TunDnsPlan.plan(
            fromLibbox = if (input.dnsMode == DNS_MODE_DISABLED) emptyList() else input.dnsServers,
            hasIpv6TunAddress = input.inet6Address.isNotEmpty(),
            derivedFromTun = input.inet4Address.firstOrNull()?.let { nextIpv4(it) },
            dnsMode = input.dnsMode,
        )
        notes += dns.notes
        return PlannedTun(
            addresses = addresses,
            routes = routes,
            notes = notes,
            excludes = excludes,
            dnsServers = dns.servers,
        )
    }

    fun nextIpv4(address: CidrPrefix): String? {
        if (!address.isIpv4()) return null
        val parts = address.address.split('.')
        if (parts.size != 4) return null
        val octets = parts.mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        val next = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
        val bumped = next + 1
        return listOf(
            (bumped ushr 24) and 0xff,
            (bumped ushr 16) and 0xff,
            (bumped ushr 8) and 0xff,
            bumped and 0xff,
        ).joinToString(".")
    }

    fun hasIpv4Default(routes: List<CidrPrefix>): Boolean =
        routes.any { it.address == "0.0.0.0" && it.prefix == 0 } ||
            (
                routes.any { it.address == "0.0.0.0" && it.prefix == 1 } &&
                    routes.any { it.address == "128.0.0.0" && it.prefix == 1 }
                )

    fun hasIpv6Default(routes: List<CidrPrefix>): Boolean =
        routes.any { it.address == "::" && it.prefix == 0 } ||
            (
                routes.any { it.address == "::" && it.prefix == 1 } &&
                    routes.any { it.address == "8000::" && it.prefix == 1 }
                )
}
