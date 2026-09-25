package app.lernet.ui.routes

/** Searchable country catalog. The source list is [app.lernet.routing.GeoRuleSets.bundled]. */
object CountryCatalog {
    const val PRIVATE = "private"
    private const val RECENT_LIMIT = 6

    /** Shortcuts only. The picker still lists every bundled code. */
    val frequent: List<String> = listOf(
        "ru", "by", "kz", "ua", "us", "de", "nl", "fi", "se", "cn", "ir",
        "tr", "gb", "fr", "pl", "am", "ge", "az",
    )

    private val aliases: Map<String, String> = mapOf(
        "рф" to "ru",
        "сша" to "us",
        "кнр" to "cn",
        "кндр" to "kp",
        "оаэ" to "ae",
        "юар" to "za",
        "uk" to "gb",
    )

    fun groups(query: String, recent: List<String>, labels: List<CountryLabel>): CountryGroups {
        val matched = labels.filter { matches(query, it) }
        if (query.trim().isNotEmpty()) {
            return CountryGroups(emptyList(), emptyList(), sort(matched))
        }
        val present = labels.map { it.code }.toHashSet()
        val pinned = frequent.filter { it in present }
        val pinnedSet = pinned.toHashSet()
        val latest = recent.map { it.lowercase() }.distinct().filter { it in present && it !in pinnedSet }
        return CountryGroups(pinned, latest, sort(matched))
    }

    fun pin(recent: List<String>, code: String): String {
        val lowered = code.lowercase()
        val next = listOf(lowered) + recent.filterNot { it.equals(lowered, ignoreCase = true) }
        return next.take(RECENT_LIMIT).joinToString(",")
    }

    internal fun matches(query: String, label: CountryLabel): Boolean {
        val q = fold(query.trim())
        val code = fold(label.code)
        val ru = fold(label.nameRu)
        val en = fold(label.nameEn)
        val short = q.length <= 2
        val byName = if (short) ru.startsWith(q) || en.startsWith(q) else ru.contains(q) || en.contains(q)
        val byCode = if (short) code == q else code.startsWith(q)
        return q.isEmpty() || aliases[q] == code || byCode || byName
    }

    private fun sort(labels: List<CountryLabel>): List<String> =
        labels.sortedWith(compareBy({ fold(it.nameRu) }, { it.code })).map { it.code }

    private fun fold(value: String): String = value.lowercase().replace('ё', 'е')
}

data class CountryLabel(val code: String, val nameRu: String, val nameEn: String)

data class CountryGroups(val frequent: List<String>, val recent: List<String>, val rest: List<String>)
