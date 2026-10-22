package app.lernet.config.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

fun interface RemoteTextFetcher {
    fun fetch(url: String): Result

    sealed class Result {
        data class Ok(
            val body: String,
            val contentType: String? = null,
            /** Response headers, names in lower case (for example `profile-update-interval`). */
            val headers: Map<String, String> = emptyMap(),
        ) : Result()

        data class Err(val message: String) : Result()
    }
}

class OkHttpTextFetcher(
    private val client: OkHttpClient = defaultClient(),
) : RemoteTextFetcher {
    override fun fetch(url: String): RemoteTextFetcher.Result {
        val request = Request.Builder().url(url).get().build()
        return try {
            // While the connection is on, subscriptions are fetched only through the VPN.
            AppTraffic.client(client).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    RemoteTextFetcher.Result.Err("HTTP ${response.code}")
                } else {
                    readCapped(response)
                }
            }
        } catch (ioe: IOException) {
            RemoteTextFetcher.Result.Err(
                when (ioe) {
                    is java.net.SocketTimeoutException -> "Время ожидания истекло"
                    is AppTraffic.ClosedException -> AppTraffic.CLOSED_MESSAGE
                    else -> "Не удалось загрузить: сетевая ошибка"
                },
            )
        }
    }

    private fun readCapped(response: Response): RemoteTextFetcher.Result {
        val type = response.header("Content-Type")
        val headers = response.headers.names().associate { name -> name.lowercase() to response.headers[name].orEmpty() }
        val source = response.body?.source() ?: return RemoteTextFetcher.Result.Ok("", type, headers)
        val buf = Buffer()
        // One read() returns at most one segment (8 KiB): read until the end, or until the cap is passed.
        while (buf.size <= MAX_BODY_BYTES) {
            if (source.read(buf, READ_CHUNK) == -1L) break
        }
        if (buf.size > MAX_BODY_BYTES) {
            return RemoteTextFetcher.Result.Err("Ответ больше $MAX_BODY_BYTES байт")
        }
        return RemoteTextFetcher.Result.Ok(buf.readUtf8(), type, headers)
    }

    companion object {
        const val MAX_BODY_BYTES = 8L * 1024L * 1024L
        private const val READ_CHUNK = 64L * 1024L

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}
