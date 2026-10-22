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
}
