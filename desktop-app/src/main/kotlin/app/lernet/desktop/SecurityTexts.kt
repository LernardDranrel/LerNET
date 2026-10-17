package app.lernet.desktop

/** User-facing texts of the security settings, kept in one place. */
internal object SecurityTexts {
    const val PANEL_TAG = "ЗАЩИТА"
    const val PANEL_TITLE = "Защита от утечек"
    const val PANEL_BODY = "Действует в режиме VPN."
    const val CRASH_GUARD = "Не выпускать трафик мимо VPN при сбое"
    const val CRASH_GUARD_HINT =
        "Пока VPN включён и LerNET восстанавливает подключение, Windows не пускает трафик в обход. " +
            "Локальная сеть и принтеры остаются доступны. Кнопка «Стоп» сразу возвращает обычную связь."
    const val STRICT_ROUTE = "Не пускать DNS-запросы мимо VPN"
    const val STRICT_ROUTE_HINT =
        "Если выключить, Windows может спрашивать адреса сайтов у провайдера в обход VPN. " +
            "Выключайте, только если без этого перестала работать какая-то программа."
    const val APPLIES_ON_RECONNECT = "Изменения вступят в силу при следующем подключении."
    const val GUARD_PROBLEM = "Защита при сбое не включилась. VPN работает, подробности — в журнале."
    const val GUARD_NETWORK_CLOSED =
        "LerNET не нашёл свой сетевой адаптер. Чтобы не было утечки, интернет закрыт. Нажмите «Стоп» и подключитесь снова."
    const val GUARD_JOURNAL_PREFIX = "Защита при сбое не работает: "

    const val PROXY_TAG = "ПРОКСИ"
    const val PROXY_TITLE = "Пароль локального прокси"
    const val PROXY_BODY =
        "Без логина и пароля другие программы не могут пользоваться прокси LerNET. " +
            "Если оставить поля пустыми, при каждом подключении создаются новые случайные логин и пароль."
    const val PROXY_RULES =
        "До 64 символов, латиница, цифры и знаки, без пробелов и двоеточия. Действует со следующего подключения."
    const val PROXY_ADDRESS = "Адрес"
    const val PROXY_LOGIN = "Логин"
    const val PROXY_PASSWORD = "Пароль"
    const val COPY = "Копировать"
    const val SAVE = "Сохранить"

    const val IP_CHECK_TITLE = "Проверить внешний IP?"
    const val IP_CHECK_BODY =
        "LerNET спросит у сервиса api.ipify.org, с какого адреса пришёл запрос. Если VPN включён, запрос идёт через VPN, " +
            "и сервис увидит адрес VPN. Если VPN выключен — ваш обычный адрес. Список программ, адаптеров и отчёт не передаются."
}
