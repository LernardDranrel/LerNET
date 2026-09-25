package app.lernet.config.model

enum class ProfileSource {
    VLESS,
    JSON_URL,
    JSON_PASTE,
    SUBSCRIPTION,
    ;

    companion object {
        fun fromStorage(raw: String): ProfileSource =
            entries.firstOrNull { it.name == raw } ?: error("unknown ProfileSource: $raw")
    }
}
