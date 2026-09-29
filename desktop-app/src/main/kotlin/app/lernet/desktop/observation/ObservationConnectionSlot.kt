package app.lernet.desktop.observation

import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

/** Prevents a connection created after window disposal from escaping cleanup. */
internal class ObservationConnectionSlot : AutoCloseable {
    private var closed = false
    private var connection: HttpURLConnection? = null
    private var generation = 0L

    @Synchronized
    fun beginRequest(): Long {
        if (closed) throw CancellationException("Observation window was closed")
        check(connection == null) { "Another observation request is still registered" }
        return ++generation
    }

    @Synchronized
    fun isCurrent(request: Long) = !closed && request == generation

    @Synchronized
    fun attach(value: HttpURLConnection, request: Long) {
        if (!isCurrent(request)) {
            value.disconnect()
            throw CancellationException("Observation request was cancelled")
        }
        check(connection == null) { "Another observation request is still registered" }
        connection = value
    }

    @Synchronized
    fun release(request: Long) { if (request == generation) connection = null }

    fun cancelRequest() {
        val active = synchronized(this) {
            generation++
            connection.also { connection = null }
        }
        active?.disconnect()
    }

    override fun close() {
        val active = synchronized(this) {
            closed = true
            generation++
            connection.also { connection = null }
        }
        active?.disconnect()
    }
}
