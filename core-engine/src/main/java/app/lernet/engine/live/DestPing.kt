package app.lernet.engine.live

object DestPing {
    fun parseHostPort(destination: String): Pair<String, Int>? {
        val value = destination.trim()
        val colon = value.lastIndexOf(':')
        if (colon <= 0 || colon == value.lastIndex) return null
        val host = value.substring(0, colon).trim().trim('[', ']')
        val port = value.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    fun tcp(
        host: String,
        port: Int,
        timeoutMs: Int,
        nowMs: () -> Long,
        connect: (host: String, port: Int, timeoutMs: Int) -> Unit,
    ): Result<Long> {
        val start = nowMs()
        return runCatching {
            connect(host, port, timeoutMs)
            (nowMs() - start).coerceAtLeast(0L)
        }
    }
}
