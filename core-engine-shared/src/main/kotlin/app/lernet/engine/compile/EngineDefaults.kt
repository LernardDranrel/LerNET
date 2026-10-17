package app.lernet.engine.compile

/** Defaults the client applies when a profile does not supply these values. */
data class EngineDefaults(
    val tunMtu: Int = ConfigAssembler.TUN_MTU,
    val xmuxConcurrency: String = XhttpMode.MUX_CONCURRENCY,
    val directDnsServer: String = DnsBlock.DIRECT_SERVER,
    /** Windows VPN mode: forbid DNS and IPv6 on other adapters. Android ignores it. */
    val strictRoute: Boolean = true,
    /** Opt-in: split the first TLS message into several TLS records (core `record_fragment`). */
    val tlsRecordFragment: Boolean = false,
    /** Reality key exchange: "" (as the fingerprint), "hybrid" or "classical". */
    val realityKeyShare: String = "",
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
