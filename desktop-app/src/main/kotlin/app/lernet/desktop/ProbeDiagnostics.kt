package app.lernet.desktop

import app.lernet.config.net.LocalProxyAccess
import app.lernet.config.net.LocalProxyHttp
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

internal object ProbeDiagnostics {
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val sensitive = Regex("(?i)([\\\"']?(?:password|private_key|public_key|short_id|uuid|token|secret|authorization)[\\\"']?\\s*[:=]\\s*)(?:[\\\"'][^\\\"']*[\\\"']|[^\\s,;]+)")
    private val urls = Regex("https?://[^\\s<>\\\"']+")
    private val profiles = Regex("(?i)(?:vless|vmess|trojan|ss|socks5?|hysteria2?|tuic)://[^\\s<>\\\"']+")
    private val bearer = Regex("(?i)\\bBearer\\s+[-A-Za-z0-9._~+/]+=*")

    fun clean(line: String): String = urls.replace(sensitive.replace(bearer.replace(profiles.replace(ansi.replace(line, ""), "[ссылка конфига скрыта]"), "Bearer [скрыто]"), "$1[скрыто]")) { match ->
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
    fun httpDetail(url: String, inboundPort: Int, remainingMs: Int, access: LocalProxyAccess? = null): String {
        if (remainingMs < 500) return "HTTP-диагностика не повторялась: бюджет проверки исчерпан."
        var client: OkHttpClient? = null
        return try {
            val uri = URI(url)
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) { "Нужен HTTP(S)-адрес без учётных данных" }
            val timeout = remainingMs.toLong()
            val builder = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            // The temporary inbound requires the same login/password as the real proxy.
            client = if (access != null) LocalProxyHttp.via(builder, access).build()
                else builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", inboundPort))).build()
            val code = client.newCall(Request.Builder().url(url).get().build()).execute().use { it.code }
            "HTTP через тот же временный прокси: ответ $code. Ответ получен после ошибки delay API; результаты двух запросов могут отличаться."
        } catch (error: Exception) { "HTTP через тот же временный прокси: ${cause(error)}" }
        finally {
            client?.connectionPool?.evictAll()
            client?.dispatcher?.executorService?.shutdown()
        }

    }
}
