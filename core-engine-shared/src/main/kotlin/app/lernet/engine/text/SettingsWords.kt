package app.lernet.engine.text

/**
 * The words of «Настройки» and «Для опытных», one copy for both platforms (see [HomeWords]).
 * Only what really differs by platform stays outside: leak protection (Android does it in its own settings,
 * Windows in LerNET), administrator rights, sending versus saving the journal, the installer.
 */
object SettingsWords {
    const val TITLE = "Настройки"
    const val GROUP_CONNECTION = "Подключение"
    const val GROUP_LEAKS = "Защита от утечек"
    const val GROUP_HELP = "Помощь"
    const val GROUP_APP = "Приложение"

    // «Подключение»
    const val MODE = "Режим"
    const val MODE_VPN = "Полный VPN"
    const val MODE_PROXY = "Прокси"
    const val MODE_NOTE = "По умолчанию — полный VPN для всего устройства. Если у конфига задан свой режим, действует он."
    const val MODE_NEXT = "Новый режим включится при следующем подключении."
    const val PROXY_NOT_RUNNING = "Адрес, логин и пароль для копирования появятся здесь, когда прокси запущен."
    const val PROXY_ACCESS = "Логин и пароль прокси"
    const val PROXY_HELP = "Без логина и пароля другие приложения не могут пользоваться прокси LerNET. " +
        "Если оставить поля пустыми, при каждом подключении создаются новые случайные логин и пароль."
    const val LOGIN = "Логин"
    const val PASSWORD = "Пароль"
    const val PROXY_RULES = "До 64 символов, латиница, цифры и знаки, без пробелов и двоеточия. Действует со следующего подключения."
    const val APPS = "Приложения через VPN"
    const val APPS_ALL = "все"
    fun appsSome(count: Int) = "кроме $count"
    const val APPS_HELP = "Отмеченные приложения идут через VPN. Снимите галочку — приложение пойдёт напрямую, мимо VPN. " +
        "Действует в режиме VPN со следующего подключения. В Эксперте приложения задаются правилами схемы."
    const val APPS_RECONNECT = "Чтобы изменения подействовали, переподключите VPN."
    const val APPS_SEARCH = "Название приложения"
    const val APPS_ALL_ON = "Все через VPN"
    const val BOOT = "Включать VPN после перезагрузки"
    const val BOOT_HINT = "Если VPN был включён, LerNET подключит его снова. Кнопка «Отключить» отменяет это до следующего подключения."
    const val DNS = "DNS (адреса сайтов)"
    const val DNS_TUNNEL_SHORT = "через VPN"
    const val DNS_DIRECT_SHORT = "напрямую"
    const val DNS_PROFILE_SHORT = "из конфига"
    const val DNS_TUNNEL = "DNS через VPN (по умолчанию)"
    const val DNS_DIRECT = "DNS напрямую, мимо VPN (сервер из настроек) — сеть видит имена сайтов"
    const val DNS_PROFILE = "DNS из конфига"
    const val DNS_DEFAULT_HELP = "Используется при импорте. У существующих конфигов DNS меняется отдельно."

    // «Помощь», «Приложение»
    const val NETWORK = "Анализ сети"
    const val UPDATES = "Проверять обновления раз в сутки"
    const val UPDATES_NOW = "Проверить сейчас"
    const val EXPERTS = "Для опытных"
    const val VERSION = "Версия"
    const val CORE = "Ядро"
    const val COPY = "Скопировать"
    const val COPIED = "Скопировано"

    // «Для опытных»
    const val EXPERTS_SCOPE = "Используются, если конфиг не задаёт своё значение. " +
        "Изменения MTU, соединений XHTTP и адреса DNS вступят в силу при следующем подключении."
    const val AUTO_APPLY = "Значения применяются сами, как только они правильные."
    const val DNS_SECTION = "DNS · адреса сайтов"
    const val DNS_EXPLAINED = "Куда отправлять запросы имён сайтов. По умолчанию — через VPN: сеть не видит, какие сайты вы открываете. " +
        "Если на каком-то сервере DNS через VPN не отвечает, выберите для него «DNS напрямую»."
    const val DIRECT_DNS = "Резервный DNS по умолчанию"
    const val DIRECT_DNS_HELP = "IPv4-адрес DNS в исходной сети. Подставляется, когда конфиг не задаёт прямой DNS."
    const val DNS_MULTIPLE_HELP = "В этом поле поддерживается один IPv4-адрес. Несколько DNS можно задать в JSON конфига вместе " +
        "с правилами выбора и включить «DNS из конфига». Список адресов через запятую здесь не применяется."
    const val XHTTP_TITLE = "XHTTP · соединения с сервером"
    const val XHTTP_EXPLAINED = "Сколько соединений с сервером держит XHTTP. По умолчанию два: все запросы идут по ним, " +
        "новые соединения не открываются пачкой."
    const val XHTTP_SINGLE = "Один канал"
    const val XHTTP_TUNING = "Включите «Один канал», если в вашей сети глушат новые соединения: весь трафик пойдёт одним соединением. " +
        "Минус: одна медленная загрузка может притормозить остальное."
    const val XHTTP_DEFAULT_HELP = "Подставляется, только если конфиг сервера не задаёт своё."
    const val MTU_TITLE = "MTU · размер пакета"
    const val MTU_HELP = "Наибольший размер пакета в VPN. Меняйте, только если сайты открываются частично."
    const val MTU = "MTU"
    const val BYTES = "байт"
    const val MTU_RANGE = "1280–9000 байт · по умолчанию 1500"
    const val MTU_TUNING = "Если соединение есть, но страницы или загрузки зависают, сравните 1500 и 1400; при необходимости " +
        "попробуйте 1280. После изменения переподключитесь и повторите ту же загрузку. Выберите наибольшее значение, " +
        "при котором ваша сеть работает стабильно."
    const val TLS_TITLE = "Рукопожатие TLS"
    const val TLS_HELP = "Для сетей, где фильтр режет или теряет первое сообщение защищённого соединения. Действует для узлов " +
        "VLESS, VMess, Trojan с TLS или Reality. Значения, записанные в самом конфиге, важнее. Вступит в силу при следующем подключении."
    const val TLS_FRAGMENT = "Дробить первое сообщение TLS"
    const val TLS_FRAGMENT_HELP = "Разбивает приветствие на несколько частей. Помогает, если соединение рвётся или молчит сразу " +
        "после начала рукопожатия. Немного замедляет подключение. По умолчанию выключено."
    const val KEY_SHARE = "Обмен ключами Reality"
    const val KEY_SHARE_AUTO = "Как в отпечатке"
    const val KEY_SHARE_HYBRID = "Гибридный"
    const val KEY_SHARE_CLASSICAL = "Короткий"
    const val KEY_SHARE_HELP = "«Короткий» убирает большой постквантовый ключ из приветствия — оно меньше и реже теряется. " +
        "Работает только с серверами Xray старше 26.9.8; с новыми серверами связь не установится."
    const val JOURNAL = "Журнал"
    const val JOURNAL_HELP = "Сколько места может занять журнал на устройстве. На работу VPN не влияет."
    const val JOURNAL_LIMIT = "Размер журнала"
    const val MEGABYTES = "МБ"
    const val APPLY = "Применить"
    const val JOURNAL_CONFIRM_TITLE = "Изменить предел журнала"
    fun journalConfirm(mb: Int) = "Журнал будет занимать не больше $mb МБ. Если сейчас он больше, самые старые записи удалятся."
    const val LOG_LEVEL = "Подробность журнала"
    const val LOG_WARN = "Предупреждения"
    const val LOG_INFO = "Инфо"
    const val LOG_DEBUG = "Отладка"
    const val LOG_WARN_HELP = "По умолчанию. Только предупреждения и ошибки: в журнале меньше сведений о том, куда вы подключаетесь."
    const val LOG_INFO_HELP = "Основные события, предупреждения и ошибки. В журнале больше подробностей о ваших подключениях."
    const val LOG_DEBUG_HELP = "Подробная запись для поиска ошибки. Сведений о подключениях больше всего."
    const val LOG_REDACT = "Ключи, пароли и ссылки на серверы вырезаются до записи в журнал. Журнал никуда сам не уходит."
    fun logHelp(level: String) = when (level) {
        "info" -> LOG_INFO_HELP
        "debug" -> LOG_DEBUG_HELP
        else -> LOG_WARN_HELP
    }
}
