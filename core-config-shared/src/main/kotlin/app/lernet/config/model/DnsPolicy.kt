package app.lernet.config.model

enum class DnsPolicy {
    UNDERLAY,
    PROFILE,
    ;

    companion object {
        fun fromStorage(raw: String?): DnsPolicy =
            entries.firstOrNull { it.name == raw } ?: UNDERLAY
    }
}
