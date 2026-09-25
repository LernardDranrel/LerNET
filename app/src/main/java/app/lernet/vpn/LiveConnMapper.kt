package app.lernet.vpn

import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveConnStatus
import app.lernet.engine.live.LiveVia
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.StringIterator

object LiveConnMapper {
    fun from(connection: Connection): LiveConn {
        val destRaw = connection.displayDestination().orEmpty().ifBlank { connection.destination.orEmpty() }
        val parsed = parseDest(destRaw)
        val process = runCatching { connection.processInfo }.getOrNull()
        val packages = process?.let { names(it.packageNames()) }.orEmpty()
        val pid = runCatching { process?.processID }.getOrNull()?.takeIf { it > 0L }
        val uid = uidOf(runCatching { process?.userID }.getOrNull(), pid)
        val outbound = connection.outbound.orEmpty().ifBlank { connection.fromOutbound.orEmpty() }
        val type = connection.outboundType.orEmpty()
        val uplink = connection.uplinkTotal.takeIf { it > 0 } ?: connection.uplink
        val downlink = connection.downlinkTotal.takeIf { it > 0 } ?: connection.downlink
        val status = statusOf(connection.closedAt > 0L, uplink, downlink)
        return LiveConn(
            id = connection.id.orEmpty().ifBlank { destRaw },
            app = packages.firstOrNull().orEmpty().ifBlank { process?.processPath.orEmpty().ifBlank { "?" } },
            uid = uid,
            destHost = parsed.first,
            destPort = parsed.second,
            domain = connection.domain?.takeIf { it.isNotBlank() },
            outbound = outbound,
            via = LiveVia.of(outbound, type),
            uplink = uplink,
            downlink = downlink,
            rule = connection.rule?.takeIf { it.isNotBlank() },
            status = status,
            protocol = connection.protocol?.takeIf { it.isNotBlank() },
            pid = pid,
            createdAt = connection.createdAt,
            transport = connection.network?.takeIf { it.isNotBlank() },
            ipVersion = connection.ipVersion.takeIf { it == 4 || it == 6 },
            routeChain = runCatching { names(connection.chain()) }.getOrDefault(emptyList()),
        )
    }

    private fun uidOf(rawUid: Int?, pid: Long?): Int? = when {
        rawUid == null -> null
        rawUid > 0 -> rawUid
        rawUid == 0 && pid != null -> 0
        else -> null
    }

    private fun statusOf(closed: Boolean, uplink: Long, downlink: Long): LiveConnStatus = when {
        uplink > 0L && downlink == 0L -> LiveConnStatus.UNFINISHED
        closed -> LiveConnStatus.CLOSED
        else -> LiveConnStatus.OPEN
    }

    private fun parseDest(raw: String): Pair<String, Int> {
        val colon = raw.lastIndexOf(':')
        if (colon <= 0) return raw.ifBlank { "?" } to 0
        val host = raw.substring(0, colon).trim().trim('[', ']')
        val port = raw.substring(colon + 1).toIntOrNull() ?: 0
        return (host.ifBlank { "?" }) to port
    }

    private fun names(iterator: StringIterator?): List<String> {
        if (iterator == null) return emptyList()
        val out = mutableListOf<String>()
        while (iterator.hasNext()) {
            val name = iterator.next()
            if (!name.isNullOrBlank()) out += name
        }
        return out
    }
}
