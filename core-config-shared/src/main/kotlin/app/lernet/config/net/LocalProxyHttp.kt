package app.lernet.config.net

import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * Routes an OkHttp client through a LerNET local proxy that requires a login and password.
 *
 * Credentials are offered only to the proxy: for HTTPS OkHttp sends them in the CONNECT request
 * (preemptively), never inside the encrypted tunnel to the destination. A second 407 stops the
 * request instead of looping, and there is no fallback to a direct connection.
 */
object LocalProxyHttp {
    const val PROXY_AUTHORIZATION = "Proxy-Authorization"

    fun proxy(access: LocalProxyAccess): Proxy =
        Proxy(Proxy.Type.HTTP, InetSocketAddress(access.host, access.port))

    fun authenticator(access: LocalProxyAccess): Authenticator {
        val credential = Credentials.basic(access.username, access.password, Charsets.UTF_8)
        return Authenticator { _, response ->
            if (response.request.header(PROXY_AUTHORIZATION) != null) null
            else response.request.newBuilder().header(PROXY_AUTHORIZATION, credential).build()
        }
    }

    fun via(builder: OkHttpClient.Builder, access: LocalProxyAccess): OkHttpClient.Builder =
        builder.proxy(proxy(access)).proxyAuthenticator(authenticator(access))
}
