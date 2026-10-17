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
    const val GUARD_PROBLEM_PREFIX = "Защита при сбое не работает: "
}
