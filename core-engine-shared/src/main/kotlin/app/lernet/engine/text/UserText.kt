package app.lernet.engine.text

/**
 * The only way an exception reaches the screen (Android and Windows alike).
 *
 * Raw exception text («Required value was null.», «connect timed out», class names) never goes to the
 * UI: the technical details are written to the journal through [logger], and the person gets a plain
 * Russian sentence. Messages that LerNET wrote itself (they are in Russian) pass through unchanged.
 */
object UserText {
    /** Platform journal hook; set once at startup (LerNetLog on Android, the core journal on Windows). */
    @Volatile var logger: (context: String, error: Throwable) -> Unit = { _, _ -> }

    /** A message LerNET wrote for people: has Cyrillic letters and no stack-trace vocabulary. */
    fun isOurs(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (text.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return false
        val technical = listOf("Exception", "java.", "kotlin.", "Throwable", "null.", "at app.", "Caused by")
        return technical.none { it in text }
    }

    /** Plain reason for well-known system failures, or null. Walks the cause chain. */
    fun reason(error: Throwable?): String? {
        var current = error
        var depth = 0
        while (current != null && depth < 8) {
            when (current.javaClass.name) {
                "java.net.SocketTimeoutException", "java.util.concurrent.TimeoutException",
                "kotlinx.coroutines.TimeoutCancellationException", "java.net.http.HttpTimeoutException",
                "java.net.http.HttpConnectTimeoutException" -> return "время ожидания истекло"
                "java.net.UnknownHostException" -> return "адрес сервера не найден"
                "java.net.ConnectException" -> return "сервер не принимает подключение"
                "java.net.NoRouteToHostException" -> return "нет пути до сервера"
                "java.net.PortUnreachableException" -> return "сервер не принимает подключение"
                "javax.net.ssl.SSLHandshakeException", "javax.net.ssl.SSLException",
                "javax.net.ssl.SSLPeerUnverifiedException" -> return "защищённое соединение не установилось"
                "java.nio.file.AccessDeniedException", "java.lang.SecurityException" -> return "нет доступа"
                "java.io.FileNotFoundException", "java.nio.file.NoSuchFileException" -> return "файл не найден"
                "java.net.SocketException" -> return "соединение прервалось"
                "java.io.EOFException" -> return "соединение прервалось"
            }
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return null
    }

    /**
     * Sentence for the screen. [fallback] is used when the error is not ours and has no known reason.
     * Example: `UserText.of(error, "Не удалось проверить канал")`.
     */
    fun of(error: Throwable?, fallback: String): String {
        if (error != null) logger(fallback, error)
        val own = error?.message?.takeIf(::isOurs)
        if (own != null) return own
        return reason(error)?.replaceFirstChar { it.uppercase() } ?: fallback
    }

    /** «Импорт не выполнен: время ожидания истекло» or just «Импорт не выполнен». */
    fun withReason(prefix: String, error: Throwable?): String {
        if (error != null) logger(prefix, error)
        val detail = error?.message?.takeIf(::isOurs) ?: reason(error)
        return if (detail == null) "$prefix." else "$prefix: ${detail.trimEnd('.')}"
    }

    /** For strings already taken out of an exception elsewhere (probe results, core replies). */
    fun clean(raw: String?, fallback: String): String = raw?.takeIf(::isOurs) ?: fallback
}
