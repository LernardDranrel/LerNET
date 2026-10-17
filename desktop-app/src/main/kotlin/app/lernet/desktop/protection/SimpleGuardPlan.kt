package app.lernet.desktop.protection

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/**
 * Filters of the simple-mode guard (VPN mode only).
 *
 * Everything outside the LerNET adapter is closed except: the traffic core itself (it must reach the
 * VPN server), traffic inside this computer, the local network (without DNS, so names cannot be asked
 * past the VPN), DHCP and IPv6 neighbour discovery that keep the Wi-Fi/Ethernet link alive.
 * While the core restarts the adapter disappears and only the closed part remains.
 */
object SimpleGuardPlan {
    const val SUBLAYER_KEY = "0b4b5c3e-6f0a-4f43-9a52-5d3d1b7a2c61"

    private val LOCAL_V4 = listOf(
        "10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16, "169.254.0.0" to 16,
        "224.0.0.0" to 4, "255.255.255.255" to 32,
    )
    private val LOCAL_V6 = listOf("fe80::" to 10, "fc00::" to 7, "ff00::" to 8)

    fun filters(corePath: String, tunLuid: Long?, dhcpServicePath: String?): List<ProtectionFilter> {
        require(validPath(corePath)) { "Нужен полный путь к ядру" }
        require(tunLuid == null || tunLuid != 0L) { "Windows не сообщила идентификатор адаптера LerNET" }
        val filters = mutableListOf<ProtectionFilter>()
        ProtectionLayer.entries.forEach { layer ->
            fun add(id: String, action: ProtectionAction, weight: Long, conditions: List<ProtectionCondition>) {
                filters += ProtectionFilter(
                    key = key(layer, id), layer = layer, action = action, weight = weight, conditions = conditions,
                    providerKey = "", sublayerKey = SUBLAYER_KEY, persistent = false,
                )
            }
            add("core:${corePath.lowercase(Locale.ROOT)}", ProtectionAction.PERMIT, 1000,
                listOf(ProtectionCondition.Application(corePath)))
            if (tunLuid != null) {
                add(tunId(tunLuid), ProtectionAction.PERMIT, 900, listOf(ProtectionCondition.Interface(tunLuid, layer.outbound)))
            }
            add("loopback", ProtectionAction.PERMIT, 850, listOf(ProtectionCondition.Loopback))
            (if (layer.ipv6) LOCAL_V6 else LOCAL_V4).forEach { (address, prefix) ->
                add("lan:$address/$prefix", ProtectionAction.PERMIT, 800, listOf(
                    ProtectionCondition.RemoteSubnet(address, prefix),
                    ProtectionCondition.RemotePortNot(53),
                ))
            }
            dhcpServicePath?.takeIf(::validPath)?.let { path ->
                add("dhcp", ProtectionAction.PERMIT, 700, listOf(
                    ProtectionCondition.Application(path), ProtectionCondition.Protocol(17),
                    ProtectionCondition.Port(if (layer.ipv6) 546 else 68, local = true),
                    ProtectionCondition.Port(if (layer.ipv6) 547 else 67, local = false),
                ))
            }
            if (layer.ipv6) {
                (133..136).forEach { type ->
                    add("ndp:$type", ProtectionAction.PERMIT, 700, listOf(
                        ProtectionCondition.Protocol(58), ProtectionCondition.Port(type, local = true),
                        ProtectionCondition.Port(0, local = false),
                    ))
                }
            }
            add("block", ProtectionAction.BLOCK, 100, emptyList())
        }
        return filters
    }

    internal fun isTunPermit(filter: ProtectionFilter): Boolean =
        filter.conditions.singleOrNull() is ProtectionCondition.Interface && filter.action == ProtectionAction.PERMIT

    private fun tunId(luid: Long) = "tun:$luid"

    private fun key(layer: ProtectionLayer, id: String): String =
        UUID.nameUUIDFromBytes("LerNET simple guard v1|${layer.name}|$id".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun validPath(path: String): Boolean =
        Regex("^[A-Za-z]:\\\\[^\\u0000<>:\"|?*/]+\\.exe$", RegexOption.IGNORE_CASE).matches(path) &&
            path.split('\\').none { it == ".." || it == "." }
}
