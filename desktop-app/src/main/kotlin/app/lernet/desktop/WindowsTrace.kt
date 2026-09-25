package app.lernet.desktop

import app.lernet.engine.net.LocalGeoIp
import app.lernet.engine.net.RipeStatHopDetails
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TraceHop(
    val number: Int,
    val ip: String? = null,
    val rttMs: Int? = null,
    val country: String? = null,
    val asn: Int? = null,
    val holder: String? = null,
    val prefix: String? = null,
    val ptr: String? = null,
    val registeredTo: String? = null,
)

data class TraceSnapshot(
    val target: String = "",
    val running: Boolean = false,
    val hops: List<TraceHop> = emptyList(),
    val message: String = "",
)

class WindowsTracer : AutoCloseable {
    private val generation = AtomicLong()
    private val mutable = MutableStateFlow(TraceSnapshot())
    val state: StateFlow<TraceSnapshot> = mutable
    private val geo = LocalGeoIp { javaClass.getResourceAsStream("/hop-geoip.idx") ?: error("Нет индекса стран") }
    private val details = RipeStatHopDetails()
    private val enrichPool = Executors.newFixedThreadPool(4) { job -> Thread(job, "lernet-hop-info").apply { isDaemon = true } }
    @Volatile private var process: Process? = null

    fun trace(host: String) {
        val ticket = generation.incrementAndGet()
        process?.destroy()
        mutable.value = TraceSnapshot(target = host, running = true, message = "Определяем маршрут")
        thread(name = "lernet-windows-trace", isDaemon = true) {
            try {
                val target = InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
                    ?: error("Для tracert нужен IPv4-адрес сервера")
                if (generation.get() != ticket) return@thread
                val running = ProcessBuilder("tracert", "-4", "-d", "-h", "24", "-w", "1200", target)
                    .redirectErrorStream(true).start()
                process = running
                running.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (generation.get() != ticket) return@forEach
                        val hop = parseLine(line) ?: return@forEach
                        mutable.update { state -> state.copy(hops = (state.hops.filterNot { it.number == hop.number } + hop).sortedBy { it.number }, message = "Узел ${hop.number}") }
                        if (hop.ip != null) enrichPool.submit { enrich(hop, ticket) }
                    }
                }
                if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
                if (generation.get() == ticket) {
                    val reached = mutable.value.hops.any { it.ip == target }
                    mutable.update { it.copy(running = false, message = if (reached) "Маршрут до сервера найден" else "Сервер не ответил на tracert; промежуточные узлы показаны") }
                }
            } catch (error: Exception) {
                if (generation.get() == ticket) mutable.update { it.copy(running = false, message = error.message ?: "Ошибка tracert") }
            } finally {
                if (generation.get() == ticket) process = null
            }
        }
    }

    private fun enrich(hop: TraceHop, ticket: Long) {
        val ip = hop.ip ?: return
        val country = runCatching { geo.country(ip) }.getOrNull()
        if (generation.get() == ticket && country != null) replace(hop.number, ticket) { it.copy(country = country) }
        val info = details.lookup(ip) ?: return
        replace(hop.number, ticket) { it.copy(asn = info.asn, holder = info.holder, prefix = info.prefix,
            ptr = info.ptr, registeredTo = info.registeredTo) }
    }

    private fun replace(number: Int, ticket: Long, transform: (TraceHop) -> TraceHop) {
        if (generation.get() != ticket) return
        mutable.update { state -> state.copy(hops = state.hops.map { if (it.number == number) transform(it) else it }) }
    }

    fun cancel() {
        generation.incrementAndGet()
        process?.destroy()
        process = null
        mutable.update { it.copy(running = false, message = "Остановлено") }
    }

    override fun close() {
        cancel()
        enrichPool.shutdownNow()
    }

    companion object {
        private val hopNumber = Regex("^\\s*(\\d{1,2})\\s+(.+)$")
        private val ipPattern = Regex("(?<![0-9.])(\\d{1,3}(?:\\.\\d{1,3}){3})(?![0-9.])")
        private val rttPattern = Regex("(?:<\\s*)?(\\d+)\\s*(?:ms|мс)", RegexOption.IGNORE_CASE)

        internal fun parseLine(line: String): TraceHop? {
            val match = hopNumber.find(line) ?: return null
            val number = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..24 } ?: return null
            val tail = match.groupValues[2]
            val ip = ipPattern.findAll(tail).map { it.groupValues[1] }
                .firstOrNull { candidate -> candidate.split('.').all { it.toIntOrNull() in 0..255 } }
            val rtt = rttPattern.find(tail)?.groupValues?.get(1)?.toIntOrNull()
            if (ip == null && '*' !in tail) return null
            return TraceHop(number, ip, rtt)
        }
    }
}
