package app.lernet.engine.text

/** The config list: the phone's drawer and the Windows left column (see [HomeWords]). */
object ConfigListWords {
    const val TITLE = "Конфиги"
    /** The folder tag when automatic switching is on (the phone showed an icon only). */
    const val AUTO = "АВТО"
    const val AUTO_DESCRIPTION = "Автосмена включена"

    fun configCount(n: Int): String {
        val word = when {
            n % 100 in 11..14 -> "конфигов"
            n % 10 == 1 -> "конфиг"
            n % 10 in 2..4 -> "конфига"
            else -> "конфигов"
        }
        return "$n $word"
    }

    fun folderAnswer(ok: Int, total: Int) = "Отвечают: $ok из $total"
    fun folderChecking(running: Int, total: Int) = "Проверка: $running из $total"

    /** «Проверено 3 из 4 · отвечают: 2»; «Проверяем… · проверено 1 из 4 · отвечают: 1» while checks run. */
    fun summary(checked: Int, total: Int, ok: Int, checking: Boolean): String {
        val line = "проверено $checked из $total · отвечают: $ok"
        return if (checking) "Проверяем… · $line" else line.replaceFirstChar { it.uppercase() }
    }
}
