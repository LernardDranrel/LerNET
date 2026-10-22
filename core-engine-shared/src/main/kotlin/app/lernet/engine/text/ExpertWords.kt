package app.lernet.engine.text

/** Words of the Expert pult that both platforms show (Expert speaks on «ты»); see [HomeWords]. */
object ExpertWords {
    const val TILE_EXITS = "Конфиги"
    const val TILE_CONNECTIONS = "Соединения"
    const val TILE_PROTECTION = "Защита"
    const val PROTECTION_ON = "включена"
    const val PROTECTION_CHECK = "проверь"
    const val NO_EXITS = "ещё нет"

    // «События» → «Соединения»
    const val ACTIVITY_SEARCH = "Приложение, сайт или адрес"
    const val FILTER_ALL = "Все"
    const val FILTER_ACTIVE = "Активные"
    const val FILTER_LONG = "Длительные"
    const val FILTER_CLOSED = "Завершённые"
    const val ACTIVITY_EMPTY = "Соединений пока нет. Когда Эксперт включён, здесь появятся приложение, адрес и почему выбран такой путь."

    /** «ещё нет», «2 из 3» — the same short value on the phone tile and the Windows one. */
    fun exitsValue(working: Int, total: Int): String = if (total == 0) NO_EXITS else "$working из $total"

    /** «1 активное», «3 активных». */
    fun connectionsValue(active: Int): String =
        "$active " + if (active % 10 == 1 && active % 100 != 11) "активное" else "активных"

    // The pult: «Основной путь — напрямую · сведения»
    const val DETAILS = "сведения"
    const val HIDE_DETAILS = "скрыть сведения"
    const val NO_IPV6 = "IPv6 напрямую недоступен. Возможности VPN-выходов проверяются отдельно."

    fun family(value: app.lernet.engine.policy.DirectFamilyAvailability): String = when (value) {
        app.lernet.engine.policy.DirectFamilyAvailability.AVAILABLE -> "маршрут есть"
        app.lernet.engine.policy.DirectFamilyAvailability.LIMITED -> "есть отдельные маршруты"
        app.lernet.engine.policy.DirectFamilyAvailability.UNAVAILABLE -> "пути нет"
        app.lernet.engine.policy.DirectFamilyAvailability.UNKNOWN -> "недостаточно данных"
    }

    /** «Прямой выход · основной интерфейс wlan0: IPv4 — маршрут есть; IPv6 — пути нет» (the phone said «Direct»). */
    fun directFamilies(facts: app.lernet.engine.policy.ExpertDirectNetworkFacts): String =
        "Прямой выход${facts.interfaceName?.let { " · основной интерфейс $it" }.orEmpty()}: " +
            "IPv4 — ${family(facts.ipv4)}; IPv6 — ${family(facts.ipv6)}"

    const val RATE_UNKNOWN = "Скорость ещё не измерена"

    /** «↑ 1,5 КБ/с · ↓ 20 КБ/с»; «Скорость 2 из 3: …» when only some active connections are measured. */
    fun eventsRate(up: Long?, down: Long?, measured: Int = 0, active: Int = 0): String =
        if (up == null || down == null) RATE_UNKNOWN
        else (if (measured in 1 until active) "Скорость $measured из $active: " else "") +
            "↑ ${TrafficUnits.rate(up)} · ↓ ${TrafficUnits.rate(down)}"

    // «Конфиги»
    const val EXITS_EMPTY = "Конфигов пока нет"
    const val SLEEPING_ONLY = "Только спящие"
    const val SLEEPING_NONE = "Спящих конфигов нет. Сними фильтр, чтобы увидеть весь список."
    /** The phone said «внешний прокси», Windows «внешний выход»; the empty-page text speaks of a «выход». */
    const val EXTERNAL_ADD = "Добавить внешний выход"
    const val EXTERNAL_EDIT = "Изменить внешний выход"
}
