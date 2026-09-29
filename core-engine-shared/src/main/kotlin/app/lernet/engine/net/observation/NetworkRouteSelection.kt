package app.lernet.engine.net.observation

/** A table-based explanation, not a replacement for the operating system's route lookup. */
data class RouteSelection(
    val destination: String,
    val candidates: List<ObservedRoute>,
    val selected: ObservedRoute? = candidates.firstOrNull(),
    val error: String = "",
)

object NetworkRouteSelection {
    fun isNumericAddress(value: String): Boolean = NumericIpAddress.parse(value) != null
    /**
     * Never resolves a hostname or sends a packet. Persistent configuration and other
     * compartments are excluded: neither necessarily participates in this process's route.
     * Equal-cost results are retained because a snapshot cannot establish the OS tie-break.
     */
    fun select(
        snapshot: NetworkSnapshot,
        destination: String,
        currentCompartment: String = "1",
    ): RouteSelection {
        val address = NumericIpAddress.parse(destination)?.routingAddress()
            ?: return RouteSelection(destination, emptyList(), error = "Введите числовой IPv4 или IPv6. Имена сайтов здесь не разрешаются через DNS.")
        val routeSource = snapshot.sources.firstOrNull { it.id == "routes" }
        if (routeSource != null && (!routeSource.complete || routeSource.state !in setOf(SourceState.AVAILABLE, SourceState.EMPTY))) {
            return RouteSelection(destination, emptyList(), error = "Таблица маршрутов прочитана не полностью или недоступна. Нельзя определить приоритет пути по неполному снимку.")
        }
        val eligible = snapshot.routes.mapNotNull { route ->
            if (!route.store.equals("ActiveStore", ignoreCase = true)) return@mapNotNull null
            if (route.compartment.isNotBlank() && route.compartment != currentCompartment) return@mapNotNull null
            val prefix = IpPrefix.parse(route.prefix) ?: return@mapNotNull null
            if (!prefix.contains(address)) return@mapNotNull null
            // An absent adapter is incomplete evidence; a known down adapter is ineligible.
            if (snapshot.adapters.any { it.id == route.adapterId && !it.up }) return@mapNotNull null
            route to prefix.bits
        }
        val longest = eligible.maxOfOrNull { it.second }
            ?: return RouteSelection(destination, emptyList(), error = "В прочитанной активной таблице нет подходящего маршрута. Это не подтверждает недоступность адреса.")
        val specific = eligible.filter { it.second == longest }.map { it.first }
        if (specific.any { !it.metricsKnown }) return RouteSelection(destination, specific, selected = null,
            error = "Метрики некоторых интерфейсов недоступны. Самый точный префикс найден, но сравнить равнозначные пути по снимку нельзя.")
        val lowest = specific.minOf { totalMetric(it) }
        val candidates = specific.filter { totalMetric(it) == lowest }
            .sortedWith(compareBy({ it.adapterId }, { it.nextHop }, { it.interfaceIndex }))
        return RouteSelection(destination, candidates)
    }

    private fun totalMetric(route: ObservedRoute): Long = route.metric.toLong() + route.interfaceMetric.toLong()
}

internal class NumericIpAddress private constructor(val bytes: ByteArray) {
    /** Winsock represents IPv4 peers as mapped IPv6 on dual-stack sockets, but routes IPv4 packets. */
    fun routingAddress(): NumericIpAddress = if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) {
        NumericIpAddress(bytes.copyOfRange(12, 16))
    } else this

    companion object {
        fun parse(text: String): NumericIpAddress? {
            if (text.isEmpty() || text != text.trim()) return null
            val bytes = if (':' in text) ipv6(text) else ipv4(text)
            return bytes?.let(::NumericIpAddress)
        }

        private fun ipv4(text: String): ByteArray? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            parts.forEachIndexed { index, part ->
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                // Avoid ambiguous octal-looking input shared with other networking tools.
                if (part.length > 1 && part[0] == '0') return null
                val number = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                bytes[index] = number.toByte()
            }
            return bytes
        }

        private fun ipv6(text: String): ByteArray? {
            if (text.any { it !in "0123456789abcdefABCDEF:." }) return null
            val expandedText = if ('.' in text) {
                val lastColon = text.lastIndexOf(':')
                if (lastColon < 0) return null
                val suffix = ipv4(text.substring(lastColon + 1)) ?: return null
                val high = ((suffix[0].toInt() and 255) shl 8) or (suffix[1].toInt() and 255)
                val low = ((suffix[2].toInt() and 255) shl 8) or (suffix[3].toInt() and 255)
                text.substring(0, lastColon + 1) + high.toString(16) + ":" + low.toString(16)
            } else text
            val compressed = "::" in expandedText
            if (compressed && expandedText.indexOf("::") != expandedText.lastIndexOf("::")) return null
            val halves = expandedText.split("::")
            fun words(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                return part.split(':').map { word ->
                    if (word.isEmpty() || word.length > 4) return null
                    word.toIntOrNull(16) ?: return null
                }
            }
            val left = words(halves[0]) ?: return null
            val right = if (compressed) words(halves[1]) ?: return null else emptyList()
            val omitted = 8 - left.size - right.size
            if (compressed && omitted < 1 || !compressed && omitted != 0) return null
            val groups = left + List(omitted) { 0 } + right
            return ByteArray(16).also { bytes ->
                groups.forEachIndexed { index, word ->
                    bytes[index * 2] = (word ushr 8).toByte()
                    bytes[index * 2 + 1] = word.toByte()
                }
            }
        }
    }
}

private data class IpPrefix(val address: NumericIpAddress, val bits: Int) {
    fun contains(target: NumericIpAddress): Boolean {
        if (target.bytes.size != address.bytes.size) return false
        val full = bits / 8
        for (index in 0 until full) if (target.bytes[index] != address.bytes[index]) return false
        val remainder = bits % 8
        if (remainder == 0) return true
        val mask = 255 shl (8 - remainder)
        return (target.bytes[full].toInt() and mask) == (address.bytes[full].toInt() and mask)
    }

    companion object {
        fun parse(text: String): IpPrefix? {
            val pieces = text.split('/')
            if (pieces.size != 2) return null
            val address = NumericIpAddress.parse(pieces[0]) ?: return null
            val bits = pieces[1].toIntOrNull()?.takeIf { it in 0..address.bytes.size * 8 } ?: return null
            return IpPrefix(address, bits)
        }
    }
}
