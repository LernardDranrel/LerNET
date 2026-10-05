package app.lernet.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.lernet.vpn.EngineProcessHost
import com.google.common.truth.Truth.assertThat
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.GetURLResult
import io.nekohasekai.libbox.HTTPHeaders
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.URLTestOutboundResult
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/** Exercises the production probe without loading Go or starting an engine/service. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33],
    application = Application::class,
    instrumentedPackages = ["io.nekohasekai.libbox"],
    shadows = [
        ProbeLibboxShadow::class,
        ProbeCommandClientShadow::class,
        ProbeCommandServerShadow::class,
        ProbeUrlTestResultShadow::class,
        ProbeGetUrlResultShadow::class,
        ProbeHttpHeadersShadow::class,
    ],
)
class LibboxBoxEngineProbeTest {
    private lateinit var engine: LibboxBoxEngine
    private lateinit var client: ProbeCommandClientShadow
    private lateinit var server: ProbeCommandServerShadow

    @Before
    fun setUp() {
        val nativeClient = probeProxy(CommandClient::class.java)
        client = Shadow.extract(nativeClient)
        ProbeLibboxShadow.client = nativeClient
        ProbeLibboxShadow.created = 0
        val nativeServer = probeProxy(CommandServer::class.java)
        server = Shadow.extract(nativeServer)
        val host = object : EngineProcessHost {
            override fun start(mode: RunMode) {
                error("A probe must not start the process host")
            }

            override fun stop() {
                error("A probe must not stop the process host")
            }
        }
        engine = LibboxBoxEngine(ApplicationProvider.getApplicationContext(), host)
        LibboxBoxEngine::class.java.getDeclaredField("server").apply { isAccessible = true }.set(engine, nativeServer)
        client.urlTestResult = urlTestResult(delayMs = 0)
    }

    @After
    fun standaloneClientNeverUsesStreamingConnect() {
        assertThat(client.calls).doesNotContain("connect")
        ProbeLibboxShadow.client = null
    }

    @Test
    fun standaloneUnaryProbeAcceptsZeroDelayAndDisconnectsWithoutStreamingConnect() = runBlocking {
        val result = engine.probeOutbound(TAG, URL, TIMEOUT_MS)

        assertThat(result.getOrThrow()).isEqualTo(0)
        assertThat(ProbeLibboxShadow.created).isEqualTo(1)
        assertThat(client.urlTestArguments).containsExactly(TAG, URL, TIMEOUT_MS).inOrder()
        assertThat(client.calls).containsExactly("urlTestOutbound", "disconnect").inOrder()
    }

    @Test
    fun urlTestTlsFailureRemainsFailureAndDoesNotUseHttpFallback() = runBlocking {
        client.urlTestResult = urlTestResult(delayMs = 17, error = "TLS certificate verification failed")

        val result = engine.probeOutbound(TAG, URL, TIMEOUT_MS)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("TLS certificate verification failed")
        assertThat(client.calls).containsExactly("urlTestOutbound", "disconnect").inOrder()
    }

    @Test
    fun unaryCallFailureUsesBoundedHttpFallbackAndDisconnectsTheSameClient() = runBlocking {
        client.urlTestFailure = IOException("URLTest RPC unavailable")
        client.getUrlResult = getUrlResult(status = 204, elapsedMs = 42)

        val result = engine.probeOutbound(TAG, URL, TIMEOUT_MS)

        assertThat(result.getOrThrow()).isEqualTo(42)
        assertThat(ProbeLibboxShadow.created).isEqualTo(1)
        assertThat(client.getUrlArguments).containsExactly(TAG, URL, TIMEOUT_MS, 2_048).inOrder()
        assertThat(client.calls).containsExactly("urlTestOutbound", "getURLViaOutbound", "disconnect").inOrder()
    }

    @Test
    fun httpFallbackRejectsUnhealthyStatusAndStillDisconnects() = runBlocking {
        client.urlTestFailure = IOException("URLTest RPC unavailable")
        client.getUrlResult = getUrlResult(status = 503, elapsedMs = 42)

        val result = engine.probeOutbound(TAG, URL, TIMEOUT_MS)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("HTTP 503")
        assertThat(client.calls).containsExactly("urlTestOutbound", "getURLViaOutbound", "disconnect").inOrder()
    }

    @Test
    fun httpFallbackTlsFailureIsPreservedEvenWhenDisconnectAlsoFails() = runBlocking {
        client.urlTestFailure = IOException("URLTest RPC unavailable")
        val failure = IOException("HTTPS certificate verification failed")
        client.getUrlFailure = failure
        client.disconnectFailure = IOException("disconnect failed")

        val result = engine.probeOutbound(TAG, URL, TIMEOUT_MS)

        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(client.calls).containsExactly("urlTestOutbound", "getURLViaOutbound", "disconnect").inOrder()
    }

    @Test
    fun disconnectFailureDoesNotReplaceSuccessfulUnaryProbe() = runBlocking {
        client.disconnectFailure = IOException("disconnect failed")

        val result = engine.probeOutbound(TAG, URL, TIMEOUT_MS)

        assertThat(result.getOrThrow()).isEqualTo(0)
        assertThat(client.calls).containsExactly("urlTestOutbound", "disconnect").inOrder()
    }

    @Test
    fun unreadyCommandServerCannotClaimHealthyOrCreateProbeClient() = runBlocking {
        server.isReady = false

        val result = engine.probeOutbound(TAG, URL, TIMEOUT_MS)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("command server not ready")
        assertThat(ProbeLibboxShadow.created).isEqualTo(0)
        assertThat(client.calls).isEmpty()
    }

    private fun urlTestResult(delayMs: Int, error: String = ""): URLTestOutboundResult =
        probeProxy(URLTestOutboundResult::class.java).also {
            val shadow = Shadow.extract<ProbeUrlTestResultShadow>(it)
            shadow.delayMs = delayMs
            shadow.errorMessage = error
        }

    private fun getUrlResult(status: Int, elapsedMs: Int): GetURLResult =
        probeProxy(GetURLResult::class.java).also {
            val shadow = Shadow.extract<ProbeGetUrlResultShadow>(it)
            shadow.httpStatus = status
            shadow.durationMs = elapsedMs
        }

    companion object {
        private const val TAG = "restored-outbound"
        private const val URL = "https://example.test/generate_204"
        private const val TIMEOUT_MS = 8_000
    }
}

private fun <T> probeProxy(type: Class<T>): T =
    Shadow.newInstance(type, arrayOf(requireNotNull(Int::class.javaPrimitiveType)), arrayOf(0))

@Implements(value = Libbox::class, isInAndroidSdk = false)
class ProbeLibboxShadow {
    companion object {
        var client: CommandClient? = null
        var created = 0

        @JvmStatic
        @Implementation
        fun __staticInitializer__() = Unit

        @JvmStatic
        @Implementation
        fun newStandaloneCommandClient(): CommandClient {
            created++
            return requireNotNull(client)
        }
    }
}

@Implements(value = CommandClient::class, isInAndroidSdk = false)
class ProbeCommandClientShadow {
    val calls = mutableListOf<String>()
    var urlTestArguments: List<Any> = emptyList()
    var getUrlArguments: List<Any> = emptyList()
    var urlTestResult: URLTestOutboundResult? = null
    var urlTestFailure: Throwable? = null
    var getUrlResult: GetURLResult? = null
    var getUrlFailure: Throwable? = null
    var disconnectFailure: Throwable? = null

    @Implementation
    fun __constructor__(refnum: Int) = Unit

    @Implementation
    fun connect() {
        calls += "connect"
        throw AssertionError("Standalone Connect dereferences a missing native stream handler")
    }

    @Implementation
    fun urlTestOutbound(tag: String, url: String, timeoutMs: Int): URLTestOutboundResult? {
        calls += "urlTestOutbound"
        urlTestArguments = listOf(tag, url, timeoutMs)
        urlTestFailure?.let { throw it }
        return urlTestResult
    }

    @Implementation
    fun getURLViaOutbound(tag: String, url: String, timeoutMs: Int, maxBytes: Int, headers: HTTPHeaders): GetURLResult {
        calls += "getURLViaOutbound"
        getUrlArguments = listOf(tag, url, timeoutMs, maxBytes)
        getUrlFailure?.let { throw it }
        return requireNotNull(getUrlResult)
    }

    @Implementation
    fun disconnect() {
        calls += "disconnect"
        disconnectFailure?.let { throw it }
    }
}

@Implements(value = CommandServer::class, isInAndroidSdk = false)
class ProbeCommandServerShadow {
    var isReady = true

    @Implementation
    fun __constructor__(refnum: Int) = Unit

    @Implementation
    fun ready(): Boolean = isReady
}

@Implements(value = URLTestOutboundResult::class, isInAndroidSdk = false)
class ProbeUrlTestResultShadow {
    var delayMs = 0
    var errorMessage = ""

    @Implementation
    fun __constructor__(refnum: Int) = Unit

    @Implementation
    fun getDelay(): Int = delayMs

    @Implementation
    fun getError(): String = errorMessage
}

@Implements(value = GetURLResult::class, isInAndroidSdk = false)
class ProbeGetUrlResultShadow {
    var httpStatus = 0
    var durationMs = 0

    @Implementation
    fun __constructor__(refnum: Int) = Unit

    @Implementation
    fun status(): Int = httpStatus

    @Implementation
    fun elapsedMs(): Int = durationMs
}

@Implements(value = HTTPHeaders::class, isInAndroidSdk = false)
class ProbeHttpHeadersShadow {
    @Implementation
    fun __constructor__() = Unit
}
