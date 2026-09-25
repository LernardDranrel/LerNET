package app.lernet.routing

/**
 * Official SagerNet sing-geoip binary sets (branch `rule-set`), one file per code.
 * sing-box 1.12 removed the geoip database; a local `.srs` is what matches an IP.
 * `private` is ip_is_private, not a file. A code with no bundled file fails closed.
 */
object GeoRuleSets {
    val bundled: List<String> = loadBundled()
    private val bundledSet: Set<String> = bundled.toHashSet()

    fun isBundled(code: String): Boolean = bundledSet.contains(code.lowercase())

    fun tag(code: String): String = "geoip-${code.lowercase()}"

    fun fileName(code: String): String = "${tag(code)}.srs"

    fun countryCode(token: String): String? {
        val body = PatternSign.body(token).lowercase()
        if (body.isEmpty() || body == "private") return null
        return body
    }

    fun tags(tokens: List<String>): List<String> = tokens.mapNotNull(::countryCode).distinct().map(::tag)

    fun missing(tokens: List<String>): List<String> =
        tokens.mapNotNull(::countryCode).distinct().filterNot(::isBundled)

    private fun loadBundled(): List<String> {
        val stream = GeoRuleSets::class.java.getResourceAsStream("/geoip-codes.txt")
            ?: error("geoip-codes.txt is missing")
        return stream.bufferedReader().use { reader ->
            reader.lineSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()
        }
    }
}
