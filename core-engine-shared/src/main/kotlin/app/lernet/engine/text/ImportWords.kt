package app.lernet.engine.text

/** «Импорт» on both platforms (see [HomeWords]). */
object ImportWords {
    const val TITLE = "Импорт"
    fun intoFolder(name: String) = "Импорт в папку «$name»"
    /** One intro for both (the phone said only «Проверка до сохранения…», Windows only what can be pasted). */
    const val HINT = "Ссылка на сервер, JSON-конфиг, архив LerNET или адрес подписки. Проверим до сохранения; ошибки — у поля."
    const val INPUT_EXAMPLE = "vless://… или https://…/sub"
    const val PASTE = "Вставить из буфера"
    const val FROM_FILE = "Из файла"
    const val ACTION = "Сохранить"
    const val BUSY = "Импорт…"
    const val NAME = "Отображаемое имя"
    const val NAME_HINT = "Необязательно. Если пусто — возьмём имя из импорта."

    const val FROM_QR = "QR из файла"
    const val CHIP_VLESS = "VLESS"
    const val CHIP_JSON = "JSON"
    const val CHIP_URL = "URL"
    const val CHIP_SUB = "Подписка"
    const val QR_NONE = "В картинке нет QR-кода с конфигом"
    const val QR_FAIL = "Не удалось прочитать картинку"
}
