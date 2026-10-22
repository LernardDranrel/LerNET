package app.lernet.engine.text

/** Words of the rule schema (VPN «Правила» and Expert «Схема») on both platforms; see [HomeWords]. */
object SchemaWords {
    const val VIEW_SCHEMA = "Схема"
    const val VIEW_LIST = "Списком"
    const val ALIGN = "Выровнять схему"
    const val ROOT_TITLE = "Весь трафик"
    const val ROOT_SUBTITLE = "Начало схемы"
    /** A node with child branches: its own target is not used, the children and then ИНАЧЕ are checked. */
    const val BRANCHING = "Проверка условий → ИНАЧЕ"
    const val OTHERWISE = "ИНАЧЕ"
    const val OTHERWISE_HINT = "Если ни одно условие на этом уровне не подошло"
}
