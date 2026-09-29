package app.lernet.engine

enum class RunMode {
    PROXY,
    FULL_VPN,
    ;

    companion object {
        fun resolve(profileOverride: String?, default: RunMode): RunMode =
            entries.firstOrNull { it.name == profileOverride } ?: default
    }
}
