package app.lernet.engine.text

/** Rule cards on the schema and in the list: same condition lines on phone and Windows. */
object RouteWords {
    const val DOMAIN = "Домен"
    const val GEO = "Страна"
    const val PRIVATE = "Локальные сети"
    const val CIDR = "Сеть"
    const val APP = "Приложение"
    const val ANY = "Любой трафик"
    const val VIA = "Через VPN"
    const val DIRECT = "Напрямую"
    const val BLOCK = "Запретить"
    const val ADD_RULE = "+ Правило"

    /** One line as on the phone card: «Домен: *.corp.example · bank.example». */
    fun conditionSummary(
        domains: List<String>,
        domainSuffixes: List<String>,
        cidrs: List<String>,
        countries: List<String>,
        processes: List<String>,
        apps: List<String> = emptyList(),
    ): String {
        val parts = ArrayList<String>(5)
        val host = domains + domainSuffixes.map { "*.$it" }
        if (host.isNotEmpty()) parts += "$DOMAIN: " + host.take(3).joinToString(" · ")
        if (countries.isNotEmpty()) parts += "$GEO: " + countries.take(3).joinToString(" · ")
        if (cidrs.isNotEmpty()) parts += "$CIDR: " + cidrs.take(3).joinToString(" · ")
        if (apps.isNotEmpty()) parts += "$APP: " + apps.take(2).joinToString(" · ")
        if (processes.isNotEmpty()) parts += "$APP: " + processes.take(2).joinToString(" · ")
        return parts.take(2).joinToString(" · ").ifBlank { ANY }
    }
}
