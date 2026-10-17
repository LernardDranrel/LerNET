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
    const val GUARD_PROBLEM_PREFIX = "Защита при сбое не работает: "
}
