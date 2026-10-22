package app.lernet.engine.text

/** Expert «Проверка пути» (local rule simulation) on both platforms; Expert speaks on «ты». See [HomeWords]. */
object PathCheckWords {
    const val HINT = "Выбери соединение из истории и посмотри его путь. Для своего примера открой ручной редактор. " +
        "Это локальный разбор правил: запрос не отправляется, соединение не проверяется."
    const val CHOOSE = "Выбрать запись истории"
    const val CREATE = "Создать пример"
    const val EDIT = "Изменить параметры"
    const val HIDE_EDITOR = "Скрыть параметры"
    const val TAG_HISTORY = "Из истории"
    const val TAG_MODIFIED = "Копия с правками"
    const val TAG_NEW = "Новый пример"
    const val HISTORY_FIRST = "Выбери соединение из истории. Если история пока пуста, включи туннель или создай свой пример."
    const val RECORDED = "Как прошло тогда"
    const val CURRENT = "Как пройдёт по текущей схеме"
    const val WITH_EDITS = "С учётом несохранённых правок"
    const val RUN = "Разобрать путь"
    const val DOMAIN = "Домен, например example.com"
    const val PROCESS = "Процесс Windows, если известен"
    const val PORT = "Порт назначения, если известен"
    const val SOURCE_PORT = "Исходный порт"
    const val PROTOCOL = "Прикладной протокол"
    const val UNKNOWN = "Неизвестно"
    const val INVALID_EXAMPLE = "Открой параметры примера и исправь адрес или порт."
    const val INCOMPLETE = "Не хватает данных для точного выбора"
    const val NO_MATCH = "Подходящий путь не найден"
    const val INVALID = "В схеме есть ошибки"
}
