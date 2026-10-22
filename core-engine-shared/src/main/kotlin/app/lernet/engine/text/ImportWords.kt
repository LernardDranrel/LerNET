package app.lernet.engine.text

/** «Импорт» on both platforms (see [HomeWords]). */
object ImportWords {
    const val TITLE = "Импорт"
    fun intoFolder(name: String) = "Импорт в папку «$name»"
    const val INPUT_EXAMPLE = "vless://… или https://…/sub"
    const val PASTE = "Вставить из буфера"
    const val FROM_FILE = "Из файла"
    const val ACTION = "Сохранить"
    const val BUSY = "Импорт…"
    const val NAME = "Отображаемое имя"
    const val NAME_HINT = "Необязательно. Если пусто — возьмём имя из импорта."
}
