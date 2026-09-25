package app.lernet.engine.compile

/** Defaults the client applies when a profile does not supply these values. */
data class EngineDefaults(
    val tunMtu: Int = ConfigAssembler.TUN_MTU,
    val xmuxConcurrency: String = XhttpMode.MUX_CONCURRENCY,
    val directDnsServer: String = DnsBlock.DIRECT_SERVER,
) {
    companion object {
        fun validIpv4(value: String): Boolean {
            val octets = value.split('.')
            return octets.size == 4 &&
                octets.all { part ->
                    part.isNotEmpty() &&
                        part.all(Char::isDigit) &&
                        part.toIntOrNull()?.let { it in 0..255 } == true
                }
        }
    }
}
