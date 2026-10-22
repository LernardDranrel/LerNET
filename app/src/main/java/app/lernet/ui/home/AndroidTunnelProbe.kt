package app.lernet.ui.home

import app.lernet.config.net.LocalProxyAccess
import app.lernet.config.net.LocalProxyHttp
import app.lernet.engine.compile.ConfigAssembler
import app.lernet.engine.policy.TunnelProbeSchedule
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Measure one HTTPS request through the running profile without calling the unstable libbox URLTest JNI.
 * It goes through LerNET's internal password-protected listener of the running connection.
 *
 * The client is kept for the whole session with a long keep-alive: a repeated check reuses the
 * already open connection instead of opening a new one through the tunnel (on Reality without
 * multiplexing every new connection is a new handshake with the server, visible on the wire).
 * After a failed check the pool is dropped, so the confirming check does not reuse a dead socket.
 * Limits are 15 s per step and 20 s per request: under jamming a fresh handshake takes 5–20 s, and the
 * old 5 s limit declared live but slow tunnels dead. Same limits as Windows ([TunnelProbeSchedule]).
 */
internal object AndroidTunnelProbe {
    private const val TIMEOUT_MS = TunnelProbeSchedule.CHECK_TIMEOUT_MS
    private const val CALL_TIMEOUT_MS = TunnelProbeSchedule.CALL_TIMEOUT_MS
    private const val KEEP_ALIVE_MIN = 10L

    private var cachedAccess: LocalProxyAccess? = null
    private var cachedClient: OkHttpClient? = null

    @Synchronized
    private fun clientFor(access: LocalProxyAccess): OkHttpClient {
        cachedClient?.takeIf { cachedAccess == access }?.let { return it }
        close()
        return LocalProxyHttp.via(OkHttpClient.Builder(), access)
            .connectionPool(ConnectionPool(1, KEEP_ALIVE_MIN, TimeUnit.MINUTES))
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
            .also {
                cachedAccess = access
                cachedClient = it
            }
    }

    @Synchronized
    private fun close() {
        cachedClient?.let {
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        cachedClient = null
        cachedAccess = null
    }

    suspend fun measure(access: LocalProxyAccess?): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            checkNotNull(access) { "Нет данных внутреннего прокси" }
            val client = clientFor(access)
            val startedAt = System.nanoTime()
            client.newCall(Request.Builder().url(ConfigAssembler.ANDROID_PROBE_URL).build()).execute().use { response ->
                check(response.code == 204) { "HTTPS probe returned ${response.code}" }
            }
            ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        }.onFailure { close() }
    }
}
