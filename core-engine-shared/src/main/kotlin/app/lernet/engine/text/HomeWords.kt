package app.lernet.engine.text

/**
 * The words of the plain-mode home screen, one copy for both platforms. Android keeps them in string
 * resources (for TalkBack and plurals); `PlatformTextParityTest` checks that every resource below equals
 * the value here, so the phone and Windows cannot drift apart again.
 */
object HomeWords {
    /** The home page without a single config: the same title, text and button on both platforms. */
    const val NO_CONFIGS_TITLE = "Конфигов пока нет"
    const val NO_CONFIGS_BODY = "Добавьте ссылку на сервер, файл конфига или подписку от вашего VPN-сервиса. " +
        "После этого здесь появится кнопка подключения."
    const val ADD_CONFIG = "+ Добавить конфиг"

    const val TAP_CONNECT = "Нажмите, чтобы подключить"
    const val TAP_DISCONNECT = "Нажмите, чтобы отключить"

    // The config line (mockup m1).
    const val RULES = "Правила"
    const val CHANGE = "Сменить ›"
    const val NO_FOLDER = "без папки"
    const val AUTOSWAP = "автосмена"
    const val PROXY_MODE = "режим прокси"
    fun folder(name: String) = "папка «$name»"

    /** «папка «Работа» · автосмена · режим прокси» — the same order on both platforms. */
    fun profileSubtitle(folderName: String?, autoSwap: Boolean, proxyMode: Boolean): String = listOfNotNull(
        folderName?.let(::folder) ?: NO_FOLDER,
        AUTOSWAP.takeIf { folderName != null && autoSwap },
        PROXY_MODE.takeIf { proxyMode },
    ).joinToString(" · ")

    // The «Связь» card.
    const val LINK_TITLE = "Связь"
    const val CHECK = "Проверить"
    const val CHECKING_BUTTON = "Проверяем…"
    const val INTERNET = "Интернет через VPN"
    const val SERVER = "Сервер"
    const val SPEED = "Скорость"
    const val TOTAL = "Передано"
    const val PATH = "Путь до сервера"
    const val CHECKING = "проверяем…"
    const val UNCHECKED = "не проверено"
    const val NO_ANSWER = "нет ответа"
    const val WHEN_CONNECTED = "после подключения"
    const val PATH_CHECK = "проверить ›"
    fun works(ms: Long) = "работает · $ms мс"
    fun answers(ms: Long) = "отвечает · $ms мс"
    fun pathValue(hops: String, country: String?) = listOfNotNull(hops, country?.takeIf(String::isNotBlank)).joinToString(" · ") + " ›"

    /** «↑ 2 КБ/с ↓ 50 КБ/с» */
    fun pair(up: String, down: String) = "↑ $up ↓ $down"

    const val JOURNAL_ROW = "Журнал запросов ›"
    const val ATTENTION_LESS = "Свернуть"
    fun attentionMore(n: Int) = "Ещё $n"

    // Hold and failure on the home (the status strip has its own short forms in [StatusWords]).
    const val HOLDING = "Нет связи · VPN держим"
    const val FAILED = "Не удалось подключиться"
    const val OTHER_FOLDER = "Конфиг из другой папки"
    const val TURN_OFF = "Выключить VPN"
    const val TURN_OFF_NOTE = "Выключить VPN можно только вручную. Тогда трафик пойдёт напрямую, и LerNET покажет это."
    const val RETRY = "Повторить"
    const val OTHER_CONFIG = "Другой конфиг"
    const val DETAILS = "Подробнее"
    const val HIDE_DETAILS = "Скрыть"
    const val PROXY_NOTE = "Включён режим прокси: через VPN идут только приложения, в которых указан этот прокси. Адрес и пароль — в Настройках."
}

/** Byte counts and speeds in the same units on both platforms: Б, КБ, МБ, ГБ with one decimal from КБ up. */
object TrafficUnits {
    fun bytes(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v >= 1L shl 30 -> one(v / (1L shl 30).toDouble()) + " ГБ"
            v >= 1L shl 20 -> one(v / (1L shl 20).toDouble()) + " МБ"
            v >= 1024L -> one(v / 1024.0) + " КБ"
            else -> "$v Б"
        }
    }

    fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/с"

    /** «1,5»; «2» without «,0». Russian decimal comma whatever the system locale is. */
    private fun one(x: Double): String {
        val tenths = Math.round(x * 10)
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10},${tenths % 10}"
    }
}

/** The one-line connection status above secondary screens and pages (audit G1), the same on both platforms. */
object StatusWords {
    const val CONNECTED = "Подключено"
    fun connected(profile: String?, dnsOk: Boolean) =
        listOfNotNull(CONNECTED, profile?.takeIf(String::isNotBlank), DNS_OK.takeIf { dnsOk }).joinToString(" · ")
    const val DNS_OK = "DNS работает"
    const val CONNECTING = "Подключаемся…"
    const val RECONNECTING = "Переподключаемся…"
    const val OFF = "Отключено"
    const val FAILED = "Не удалось подключиться"
    const val NO_LINK_HELD = "Нет связи · VPN держим"
    const val HOLD = "Серверы не отвечают · VPN держим"
    const val JAMMED = "Связь глушат · держим подключение"
    const val NO_REPLY = "Подключено · нет ответа"
}

/**
 * The one line under a config in the phone's drawer and in the narrow Windows column, the same words on both:
 * «Работает · 142 мс», «Сервер отвечает · 38 мс», «Через VPN не работает», «Сервер не отвечает».
 * No protocol names: the plain mode does not speak TCP or VLESS.
 */
object ConfigCheckWords {
    enum class Tone { OK, BAD, NEUTRAL }

    data class Line(val text: String, val tone: Tone)

    const val CHECKING = "Проверяем…"

    fun line(
        checking: Boolean,
        budgetWaitMs: Long?,
        serverOk: Boolean,
        serverMs: Long?,
        serverChecked: Boolean,
        tunnelMs: Long?,
        tunnelFailed: Boolean,
    ): Line? = when {
        budgetWaitMs != null -> Line(app.lernet.engine.policy.BudgetNotice.short(budgetWaitMs), Tone.NEUTRAL)
        checking -> Line(CHECKING, Tone.NEUTRAL)
        tunnelMs != null -> Line("Работает · $tunnelMs мс", Tone.OK)
        serverOk && tunnelFailed -> Line("Через VPN не работает", Tone.BAD)
        serverOk -> Line(serverMs?.let { "Сервер отвечает · $it мс" } ?: "Сервер отвечает", Tone.NEUTRAL)
        serverChecked -> Line("Сервер не отвечает", Tone.BAD)
        else -> null
    }
}
