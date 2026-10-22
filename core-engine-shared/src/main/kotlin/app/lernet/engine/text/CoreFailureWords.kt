package app.lernet.engine.text

/**
 * The plain title of a core error line, the same as the phone's cause titles (core-engine EngineErrorMapper +
 * ConnectionCause.titleRu, checked by a test against that mapper). Windows used to show the raw core line.
 */
object CoreFailureWords {
    const val START_FAILED = "VPN не запустился"

    fun title(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "tls" in lower || "certificate" in lower || "x509" in lower || "ssl" in lower -> "Защищённое соединение не установилось"
            "handshake" in lower -> "Сервер не принял подключение"
            "connection reset" in lower || "broken pipe" in lower || "eof" in lower -> "Соединение прервалось"
            "timeout" in lower || "i/o deadline" in lower || "deadline exceeded" in lower -> "Сервер не ответил вовремя"
            "dial" in lower || "connect:" in lower || "network is unreachable" in lower || "no route" in lower ->
                "Сервер не принимает подключение"
            else -> START_FAILED
        }
    }
}
