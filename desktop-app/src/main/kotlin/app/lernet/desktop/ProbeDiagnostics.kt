package app.lernet.desktop

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI

internal object ProbeDiagnostics {
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val sensitive = Regex("(?i)([\\\"']?(?:password|private_key|public_key|short_id|uuid|token|secret|authorization)[\\\"']?\\s*[:=]\\s*)(?:[\\\"'][^\\\"']*[\\\"']|[^\\s,;]+)")
    private val urls = Regex("https?://[^\\s<>\\\"']+")
    private val profiles = Regex("(?i)(?:vless|vmess|trojan|ss|socks5?|hysteria2?|tuic)://[^\\s<>\\\"']+")
    private val bearer = Regex("(?i)\\bBearer\\s+[-A-Za-z0-9._~+/]+=*")

    fun clean(line: String): String = urls.replace(sensitive.replace(bearer.replace(profiles.replace(ansi.replace(line, ""), "[ссылка профиля скрыта]"), "Bearer [скрыто]"), "$1[скрыто]")) { match ->
        runCatching {
            val uri = URI(match.value)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        }.getOrDefault("[адрес скрыт]")
    }.take(1200)

    fun cause(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        return chain.joinToString(" → ") { clean("${it.javaClass.simpleName}: ${it.message.orEmpty()}") }.take(1600)
    }

    /** One diagnostic request through the same temporary inbound; never falls back to direct. */
    fun httpDetail(url: String, inboundPort: Int, remainingMs: Int): String {
        if (remainingMs < 500) return "HTTP-диагностика не повторялась: бюджет проверки исчерпан."
        return try {
            val uri = URI(url)
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) { "Нужен HTTP(S)-адрес без учётных данных" }
            val client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", inboundPort)))
                .connectTimeout(Duration.ofMillis(remainingMs.toLong()))
                .followRedirects(HttpClient.Redirect.NEVER).build()
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(remainingMs.toLong())).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().close()
            "HTTP через тот же временный прокси: ответ ${response.statusCode()}. Ответ получен после ошибки delay API; результаты двух запросов могут отличаться."
        } catch (error: Exception) { "HTTP через тот же временный прокси: ${cause(error)}" }

    }
}
