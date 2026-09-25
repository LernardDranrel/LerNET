package app.lernet.engine.net

/** Exclude addresses that cannot identify a public transit network. */
internal object IpScope {
    fun isSkipped(ip: String): Boolean {
        val octets = ip.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return true
        val first = octets[0]!!
        val second = octets[1]!!
        return first == 10 || first == 127 || first >= 224 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 100 && second in 64..127)
    }
}
