package app.lernet.ui.home

import app.lernet.engine.compile.ConfigAssembler
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Measure one HTTPS request through the running profile without calling the unstable libbox URLTest JNI. */
internal object AndroidTunnelProbe {
    private const val TIMEOUT_MS = 5_000

    suspend fun measure(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigAssembler.ANDROID_PROBE_PORT))
            val connection = URL(ConfigAssembler.ANDROID_PROBE_URL).openConnection(proxy) as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                val startedAt = System.nanoTime()
                check(connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                    "HTTPS probe returned ${connection.responseCode}"
                }
                ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
            } finally {
                connection.disconnect()
            }
        }
    }
}
