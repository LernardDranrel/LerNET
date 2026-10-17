package app.lernet.config.net

import java.io.IOException
import okhttp3.OkHttpClient

/**
 * Where LerNET's own requests (subscriptions, update checks, IP and network checks) go.
 *
 * While the connection is on they go only through the internal password-protected listener and
 * from there to the VPN server, so the provider does not see them. While the connection is
 * coming up or recovering they are refused instead of going out directly. With the connection
 * off they go directly, as before. The VPN server connection itself is made by sing-box and is
 * not affected.
 */
object AppTraffic {
    sealed interface Route {
        /** Connection off: normal direct requests. */
        data object Direct : Route

        /** Connection on: only through LerNET's internal listener. */
        class Through(val access: LocalProxyAccess) : Route {
            override fun toString() = "Through(${access.address})"
        }

        /** Connection starting or recovering: refuse rather than leak. */
        data object Closed : Route
    }

    /** Shown when a request is refused because the connection is recovering. */
    const val CLOSED_MESSAGE =
        "VPN переподключается. Дождитесь подключения или нажмите «Стоп», чтобы выполнить запрос напрямую."

    class ClosedException : IOException(CLOSED_MESSAGE)

    @Volatile
    var route: Route = Route.Direct
        private set

    fun set(next: Route) {
        route = next
    }

    /** True when requests must not leave the device directly. */
    val routed: Boolean get() = route != Route.Direct

    /**
     * A client for one request, built from [base] so the connection pool is shared. A routed
     * client names the internal listener as its explicit proxy, so OkHttp never reuses a pooled
     * direct connection for it (the proxy is part of the pool key) or the other way round.
     */
    fun client(base: OkHttpClient): OkHttpClient = when (val current = route) {
        Route.Direct -> base
        is Route.Through -> LocalProxyHttp.via(base.newBuilder(), current.access).build()
        Route.Closed -> throw ClosedException()
    }

    /**
     * Opens a TCP connection to [host]:[port] the way the current route allows and closes it:
     * directly with the connection off, as an authenticated CONNECT through the internal listener
     * with it on (the host name is resolved by the VPN server, not on this device).
     */
    fun tcpConnect(host: String, port: Int, timeoutMs: Int, direct: (String, Int, Int) -> Unit) {
        when (val current = route) {
            Route.Direct -> direct(host, port, timeoutMs)
            is Route.Through -> LocalProxyHttp.connectTunnel(current.access, host, port, timeoutMs)
            Route.Closed -> throw ClosedException()
        }
    }

}
