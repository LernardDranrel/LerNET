package app.lernet.config.model

enum class DnsPolicy {
    /** Network (ISP) resolver, outside the tunnel. Visible to the network; kept as an explicit choice. */
    UNDERLAY,
    /** DNS servers written in the profile, as they are. */
    PROFILE,
    /** Default: site names are resolved through the tunnel; only the server's own name uses the network resolver. */
    TUNNEL,
    ;

    companion object {
        val DEFAULT = TUNNEL

        fun fromStorage(raw: String?): DnsPolicy =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
