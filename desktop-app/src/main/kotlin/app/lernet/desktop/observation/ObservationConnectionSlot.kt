package app.lernet.desktop.observation

import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import okhttp3.Call

/** Prevents a connection created after window disposal from escaping cleanup. */
internal class ObservationConnectionSlot : AutoCloseable {
    private var closed = false
    /** Cancels the registered request: disconnect for HttpURLConnection, cancel for an OkHttp call. */
    private var connection: (() -> Unit)? = null
    private var generation = 0L

    @Synchronized
    fun beginRequest(): Long {
        if (closed) throw CancellationException("Observation window was closed")
        check(connection == null) { "Another observation request is still registered" }
        return ++generation
    }

    @Synchronized
    fun isCurrent(request: Long) = !closed && request == generation

    fun attach(value: HttpURLConnection, request: Long) = attachCancel({ value.disconnect() }, request)

    fun attach(value: Call, request: Long) = attachCancel({ value.cancel() }, request)

    @Synchronized
    private fun attachCancel(cancel: () -> Unit, request: Long) {
        if (!isCurrent(request)) {
            cancel()
            throw CancellationException("Observation request was cancelled")
        }
        check(connection == null) { "Another observation request is still registered" }
        connection = cancel
    }

    @Synchronized
    fun release(request: Long) { if (request == generation) connection = null }

    fun cancelRequest() {
        val active = synchronized(this) {
            generation++
            connection.also { connection = null }
        }
        active?.invoke()
    }

    override fun close() {
        val active = synchronized(this) {
            closed = true
            generation++
            connection.also { connection = null }
        }
        active?.invoke()
    }
}
