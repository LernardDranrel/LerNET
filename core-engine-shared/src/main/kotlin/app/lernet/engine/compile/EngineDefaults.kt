package app.lernet.engine.compile

/** Defaults the client applies when a profile does not supply these values. */
data class EngineDefaults(
    val tunMtu: Int = ConfigAssembler.TUN_MTU,
    /** XHTTP connections per server when the profile has no xmux of its own: 2, or 1 with «Один канал». */
    val xmuxChannels: Int = XhttpMode.DEFAULT_CHANNELS,
    val directDnsServer: String = DnsBlock.DIRECT_SERVER,
    /** Windows VPN mode: forbid DNS and IPv6 on other adapters. Android ignores it. */
    val strictRoute: Boolean = true,
    /** Opt-in: split the first TLS message into several TLS records (core `record_fragment`). */
    val tlsRecordFragment: Boolean = false,
    /** Reality key exchange: "" (as the fingerprint), "hybrid" or "classical". */
    val realityKeyShare: String = "",
    /**
     * Audit R3: apps that go past the VPN in VPN mode. Android: package names, taken out of the VPN by the
     * system (`exclude_package`). Windows: program names (chrome.exe), sent direct by a first route rule.
     */
    val bypassApps: List<String> = emptyList(),
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
