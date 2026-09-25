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
        data class Ok(val body: String, val contentType: String? = null) : Result()

        data class Err(val message: String) : Result()
    }
}

class OkHttpTextFetcher(
    private val client: OkHttpClient = defaultClient(),
) : RemoteTextFetcher {
    override fun fetch(url: String): RemoteTextFetcher.Result {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    RemoteTextFetcher.Result.Err("HTTP ${response.code}")
                } else {
                    readCapped(response)
                }
            }
        } catch (ioe: IOException) {
            RemoteTextFetcher.Result.Err(ioe.message ?: "Сетевая ошибка")
        }
    }

    private fun readCapped(response: Response): RemoteTextFetcher.Result {
        val type = response.header("Content-Type")
        val source = response.body?.source() ?: return RemoteTextFetcher.Result.Ok("", type)
        val buf = Buffer()
        val read = source.read(buf, MAX_BODY_BYTES + 1L)
        if (read > MAX_BODY_BYTES) {
            return RemoteTextFetcher.Result.Err("Ответ больше $MAX_BODY_BYTES байт")
        }
        return RemoteTextFetcher.Result.Ok(buf.readUtf8(), type)
    }

    companion object {
        const val MAX_BODY_BYTES = 8L * 1024L * 1024L

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }
}
