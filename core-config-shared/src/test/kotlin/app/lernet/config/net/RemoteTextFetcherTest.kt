package app.lernet.config.net

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class RemoteTextFetcherTest {
    @Test fun readsWholeBodyLargerThanOneSegmentAndHeaders() {
        val line = "trojan://password@server.example.com:443#Server\n"
        val body = line.repeat(2_000)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body).addHeader("Profile-Update-Interval", "8"))
            server.start()
            val result = OkHttpTextFetcher().fetch(server.url("/sub").toString()) as RemoteTextFetcher.Result.Ok
            assertThat(result.body.length).isEqualTo(body.length)
            assertThat(result.headers["profile-update-interval"]).isEqualTo("8")
        }
    }
}
