package app.lernet.desktop.observation

import app.lernet.desktop.ObservationExternalIp
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** In-memory connection only. Never performs DNS, HTTPS or native calls. */
class ObservationExternalIpTest {
    private class Connection(val text: String = "203.0.113.2", val code: Int = 200) :
        HttpURLConnection(URI("https://api.ipify.org").toURL()) {
        var disconnected = false
        var reads = 0
        override fun disconnect() { disconnected = true }
        override fun connect() { error("Test must not open a real connection") }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(text.toByteArray()).also { reads++ }
    }

    @Test fun freshRequestDisablesCacheAndUsesExplicitProxy() {
        val connection = Connection()
        val result = ObservationExternalIp.read(true, openConnection = { proxy ->
            assertEquals(Proxy.Type.HTTP, proxy.type()); connection
        }, countryLookup = { null })
        assertFalse(connection.useCaches)
        assertEquals("no-cache", connection.getRequestProperty("Cache-Control"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(30_000, connection.connectTimeout)
        assertTrue(connection.disconnected)
        assertTrue(result.contains("203.0.113.2"))
    }

    @Test fun redirectOrOversizedResponseCannotBecomeAnIp() {
        for (connection in listOf(Connection(code = 302), Connection("203.0.113.2" + " ".repeat(70)))) {
            val result = runCatching { ObservationExternalIp.read(false, openConnection = { connection }, countryLookup = { null }) }
            assertTrue(result.isFailure)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun lateRegistrationAfterWindowCloseIsCancelledBeforeReading() {
        val slot = ObservationConnectionSlot()
        val request = slot.beginRequest()
        slot.close()
        val connection = Connection()
        val result = runCatching {
            ObservationExternalIp.read(false, onConnection = { slot.attach(it, request) }, openConnection = { connection }, countryLookup = { null })
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, connection.reads)
        assertTrue(connection.disconnected)
    }

    @Test fun staleWorkerCannotReplaceOrReleaseNewRequestAfterRefresh() {
        val slot = ObservationConnectionSlot()
        val old = slot.beginRequest()
        slot.cancelRequest()
        val current = slot.beginRequest()
        val active = Connection()
        slot.attach(active, current)
        slot.release(old)
        val late = Connection()
        assertTrue(runCatching { slot.attach(late, old) }.exceptionOrNull() is CancellationException)
        assertTrue(late.disconnected)
        assertFalse(slot.isCurrent(old))
        assertTrue(slot.isCurrent(current))
        slot.close()
        assertTrue(active.disconnected)
    }

    @Test fun closingWindowDisconnectsItsRegisteredConnection() {
        val slot = ObservationConnectionSlot()
        val connection = Connection()
        slot.attach(connection, slot.beginRequest())
        slot.close()
        slot.close()
        assertTrue(connection.disconnected)
    }
}
