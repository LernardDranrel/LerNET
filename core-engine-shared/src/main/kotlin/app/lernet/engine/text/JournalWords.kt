package app.lernet.engine.text

/** «Журнал запросов» on both platforms: filters, rows, badges and the details (see [HomeWords]). */
object JournalWords {
    const val TITLE = "Журнал запросов"
    const val ALL = "Все"
    const val VPN = "Через VPN"
    const val DIRECT = "Напрямую"
    const val SILENT = "Без ответа"
    const val SEARCH = "Приложение или сайт"
    const val NO_REPLY = "нет ответа"
    fun rule(name: String) = "правило «$name»"
    const val BADGE_VPN = "VPN"
    const val BADGE_DIRECT = "напрямую"
    const val BADGE_BLOCK = "запрет"
    const val WHERE = "Куда"
    const val SITE = "Сайт"
    const val HOW = "Как"
    const val VIA_DIRECT = "напрямую, без VPN"
    const val VIA_VPN = "через VPN"
    const val RULE = "Правило"
    const val RULE_UNKNOWN = "правило неизвестно"
    const val STATE = "Состояние"
    const val STATE_OPEN = "идёт"
    const val STATE_DONE = "завершено"
    const val STATE_SILENT = "нет ответа"
    const val BYTES = "Передано"
    const val KIND = "Вид связи"
    const val CHAIN = "Цепочка выходов"
    const val ENCRYPTED = "Содержимое зашифрованных соединений не видно — ни LerNET, ни кому-либо по пути."
    const val EMPTY = "Запросов пока не было."
    const val NEED_VPN = "Включите VPN — здесь появятся запросы."
    const val NOTHING_FOUND = "Ничего не найдено. Измените поиск или фильтр."
    const val SYSTEM = "Системное приложение"
    const val UNKNOWN_APP = "Приложение не определено"

    /**
     * Сырые слова ядра в обычном режиме: русский перевод и серым оригинал в скобках.
     * Неизвестные имена оставляем как есть.
     */
    private val KERNEL = mapOf(
        "rule" to "правило",
        "proxy" to "через VPN",
        "direct" to "напрямую",
        "block" to "запрет",
        "reject" to "запрет",
        "final" to "итог",
        "dns-out" to "DNS",
        "uid" to "приложение",
    )

    /** «правило (rule)», «через VPN (proxy)»; неизвестное — без скобок. */
    fun kernel(raw: String): String {
        val key = raw.trim()
        if (key.isEmpty()) return key
        val lower = key.lowercase()
        val ru = KERNEL[lower]
        return if (ru != null) "$ru ($key)" else key
    }

    /** Правило в строке журнала: перевод имени, если это слово ядра. */
    fun ruleLabeled(name: String): String {
        val shown = kernel(name)
        return if (shown == name) rule(name) else "правило «$shown»"
    }

    fun uidLabeled(uid: Int): String = "приложение (uid $uid)"
}
