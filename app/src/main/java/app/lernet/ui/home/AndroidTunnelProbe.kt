package app.lernet.ui.home

import app.lernet.config.net.LocalProxyAccess
import app.lernet.config.net.LocalProxyHttp
import app.lernet.engine.compile.ConfigAssembler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Measure one HTTPS request through the running profile without calling the unstable libbox URLTest JNI.
 * It goes through LerNET's internal password-protected listener of the running connection.
 */
internal object AndroidTunnelProbe {
    private const val TIMEOUT_MS = 5_000L

    suspend fun measure(access: LocalProxyAccess?): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            checkNotNull(access) { "Нет данных внутреннего прокси" }
            val client = LocalProxyHttp.via(OkHttpClient.Builder(), access)
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
            try {
                val startedAt = System.nanoTime()
                client.newCall(Request.Builder().url(ConfigAssembler.ANDROID_PROBE_URL).build()).execute().use { response ->
                    check(response.code == 204) { "HTTPS probe returned ${response.code}" }
                }
                ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
