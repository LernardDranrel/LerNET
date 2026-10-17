package app.lernet.config.net

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class LocalProxyAccessTest {
    @Test
    fun everyConnectionGetsItsOwnRandomPair() {
        val pairs = (1..50).map { LocalProxyAccess.forUserProxy("", "") }
        assertThat(pairs.map { it.password }.toSet()).hasSize(50)
        assertThat(pairs.map { it.username }.toSet().size).isGreaterThan(40)
        pairs.forEach {
            assertThat(it.port).isEqualTo(2080)
            assertThat(it.password.length).isAtLeast(20)
            assertThat(LocalProxyAccess.valid(it.username)).isTrue()
            assertThat(LocalProxyAccess.valid(it.password)).isTrue()
        }
    }

    @Test
    fun savedPairIsUsedAndInvalidPairFallsBackToRandom() {
        assertThat(LocalProxyAccess.forUserProxy("me", "pw")).isEqualTo(LocalProxyAccess(2080, "me", "pw"))
        assertThat(LocalProxyAccess.forUserProxy("me", "").username).isNotEqualTo("me")
        assertThat(LocalProxyAccess.forUserProxy("a:b", "pw").username).isNotEqualTo("a:b")
        assertThat(LocalProxyAccess.forUserProxy("me", "with space").password).isNotEqualTo("with space")
    }

    @Test
    fun passwordNeverAppearsInTextForLogs() {
        val access = LocalProxyAccess(2080, "me", "TopSecret42")
        assertThat(access.toString()).doesNotContain("TopSecret42")
        assertThat(access.url()).isEqualTo("http://me:TopSecret42@127.0.0.1:2080")
        assertThat(LocalProxyAccess(2080, "me", "p@ss/w%rd").url()).isEqualTo("http://me:p%40ss%2Fw%25rd@127.0.0.1:2080")
    }

    @Test
    fun clientAnswersProxyChallengeOnceWithBasicCredentials() {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=\"sing-box\""))
            proxy.enqueue(MockResponse().setResponseCode(204))
            proxy.start()
            val access = LocalProxyAccess(proxy.port, "me", "pw")
            val client = LocalProxyHttp.via(OkHttpClient.Builder(), access).build()
            client.newCall(Request.Builder().url("http://target.invalid/check").build()).execute().use {
                assertThat(it.code).isEqualTo(204)
            }
            val first = proxy.takeRequest(5, TimeUnit.SECONDS)!!
            val second = proxy.takeRequest(5, TimeUnit.SECONDS)!!
            assertThat(first.getHeader("Proxy-Authorization")).isNull()
            assertThat(second.getHeader("Proxy-Authorization")).isEqualTo("Basic bWU6cHc=")
            assertThat(second.requestLine).isEqualTo("GET http://target.invalid/check HTTP/1.1")
        }
    }

    @Test
    fun wrongPasswordIsNotRetriedForever() {
        MockWebServer().use { proxy ->
            repeat(3) { proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic")) }
            proxy.start()
            val client = LocalProxyHttp.via(OkHttpClient.Builder(), LocalProxyAccess(proxy.port, "me", "bad")).build()
            client.newCall(Request.Builder().url("http://target.invalid/").build()).execute().use {
                assertThat(it.code).isEqualTo(407)
            }
            assertThat(proxy.requestCount).isEqualTo(2)
        }
    }
}
