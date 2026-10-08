package app.lernet.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.Func
import io.nekohasekai.libbox.LocalDNSTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Never resolve through the active VPN or substitute an external public resolver. */
internal class UnderlayDnsTransport(context: Context) : LocalDNSTransport {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= 29

    private fun underlyingNetwork(): Network {
        val selected = DefaultNetworkMonitor.underlyingNetwork() ?: manager.activeNetwork
        val caps = selected?.let(manager::getNetworkCapabilities)
        require(selected != null && caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            "DNS исходной сети недоступен: физическое подключение не определено"
        }
        return selected
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        // Android 8–9 exposes only IP lookup on a selected Network. Do not use the
        // process-wide resolver, which would send the request back into our VPN.
        val selected = underlyingNetwork()
        val lookup = legacyLookups.submit<Array<java.net.InetAddress>> { selected.getAllByName(domain) }
        ctx.onCancel(object : Func {
            override fun invoke() {
                lookup.cancel(true)
            }
        })
        try {
            val addresses = lookup.get(30, TimeUnit.SECONDS).filter {
                network != "ip4" || it.address.size == 4
            }.filter { network != "ip6" || it.address.size == 16 }
            ctx.success(addresses.mapNotNull { it.hostAddress }.joinToString("\n"))
        } finally {
            lookup.cancel(true)
        }
    }

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            exchangeRaw(ctx, message, underlyingNetwork())
        } else {
            error("Raw DNS requires Android 10")
        }
    }

    companion object {
        // Legacy blocking APIs cannot cancel the OS lookup itself. Keep both workers
        // and queued work finite, and never occupy a native caller beyond its budget.
        private val legacyLookups = java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(16),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "lernet-underlay-dns").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    @RequiresApi(29)
    private fun exchangeRaw(ctx: ExchangeContext, message: ByteArray, selected: Network) {
        val cancel = CancellationSignal()
        val done = CountDownLatch(1)
        val finished = AtomicBoolean()
        var answer: ByteArray? = null
        var failure: Exception? = null
        ctx.onCancel(object : Func {
            override fun invoke() {
                if (finished.compareAndSet(false, true)) {
                    failure = java.util.concurrent.CancellationException("DNS query cancelled")
                    cancel.cancel()
                    done.countDown()
                }
            }
        })
        try {
            DnsResolver.getInstance().rawQuery(
                selected, message, DnsResolver.FLAG_EMPTY, Executor { it.run() }, cancel,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(response: ByteArray, rcode: Int) {
                        if (finished.compareAndSet(false, true)) {
                            answer = response
                            done.countDown()
                        }
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (finished.compareAndSet(false, true)) {
                            failure = error
                            done.countDown()
                        }
                    }
                },
            )
            if (!done.await(30, TimeUnit.SECONDS)) throw java.net.SocketTimeoutException("DNS исходной сети: ожидание 30 секунд")
            failure?.let { throw it }
            ctx.rawSuccess(requireNotNull(answer) { "DNS исходной сети не вернул ответ" })
        } finally {
            finished.set(true)
            cancel.cancel()
        }
    }
}
