package app.lernet.engine.net

import app.lernet.engine.redact.LerNetLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One traceroute reply. [country] is an ISO code or null.
 * [asn] and [org] come only from a real registry cache; both stay null on a miss.
 */
data class HopStop(
    val address: String?,
    val country: String?,
    val timedOut: Boolean,
    val asn: Int? = null,
    val org: String? = null,
    val rttMs: Int? = null,
    val name: String? = null,
    val prefix: String? = null,
    val registeredTo: String? = null,
    /** Destination stub after a fully silent ICMP walk under live TCP — not a mapped hop. */
    val synthetic: Boolean = false,
)

/** Connect-path traceroute note when the channel is up. */
enum class HopSilence {
    PARTIAL,
    ALL,
}

/** Compact Connect strip: phone → country chips / `*` gaps. */
sealed class HopChip {
    data object Phone : HopChip()

    data class Country(val code: String) : HopChip()

    data object Timeout : HopChip()

    /** Answered hop without a known ISO country — never invent a flag. */
    data object Opaque : HopChip()

    /** TCP reached the server; this does not claim an ICMP hop or country. */
    data object TcpDestination : HopChip()

    /** Live probe in flight — strip pulse, not a fake hop. */
    data object Awaiting : HopChip()
}

data class HopCard(
    val index: Int,
    val title: String?,
    val country: String?,
    val rttMs: Int?,
    val address: String?,
    val timedOut: Boolean,
    val synthetic: Boolean,
    val name: String? = null,
    val asn: Int? = null,
    val org: String? = null,
    val prefix: String? = null,
    val registeredTo: String? = null,
)

object HopPath {
    const val MAX_HOPS = 30

    /**
     * Seconds for `ping -W`. Windows tracert waits several seconds on silent hops;
     * 1s was too aggressive for user trust. Documented midpoint of the 3–10s range.
     */
    const val PING_WAIT_SEC = 5

    fun countryOrNull(raw: String?): String? {
        val code = raw?.trim()?.uppercase() ?: return null
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
        return code
    }

    fun silence(stops: List<HopStop>, channelUp: Boolean): HopSilence? {
        if (!channelUp || stops.isEmpty()) return null
        val mapped = mapped(stops)
        if (mapped.isEmpty()) return HopSilence.ALL
        val answered = answered(mapped)
        if (answered.isEmpty()) return HopSilence.ALL
        return if (mapped.any { it.timedOut }) HopSilence.PARTIAL else null
    }

    fun mapped(stops: List<HopStop>): List<HopStop> = stops.filterNot { it.synthetic }

    fun answered(stops: List<HopStop>): List<HopStop> =
        stops.filter { !it.synthetic && !it.timedOut && !it.address.isNullOrBlank() }

    fun chips(stops: List<HopStop>, awaiting: Boolean = false): List<HopChip> {
        val out = mutableListOf<HopChip>(HopChip.Phone)
        mapped(stops).forEach { stop -> out += chipOf(stop) }
        stops.lastOrNull { it.synthetic && !it.timedOut }?.let { dest ->
            val code = countryOrNull(dest.country)
            out += if (code != null) HopChip.Country(code) else HopChip.Opaque
            out += HopChip.TcpDestination
        }
        if (awaiting) out += HopChip.Awaiting
        return out
    }

    fun cards(stops: List<HopStop>): List<HopCard> =
        stops.mapIndexed { index, hop ->
            HopCard(
                index = index + 1,
                title = displayTitle(hop),
                country = countryOrNull(hop.country),
                rttMs = hop.rttMs?.takeIf { !hop.timedOut },
                address = hop.address?.takeIf { it.isNotBlank() },
                timedOut = hop.timedOut && !hop.synthetic,
                synthetic = hop.synthetic,
                name = hop.name,
                asn = hop.asn,
                org = hop.org,
                prefix = hop.prefix,
                registeredTo = hop.registeredTo,
            )
        }

    fun displayTitle(hop: HopStop): String? {
        if (hop.timedOut && !hop.synthetic) return null
        hop.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return asnLabel(hop.asn, hop.org)
    }

    fun flagEmoji(code: String): String? {
        val cc = countryOrNull(code) ?: return null
        val a = 0x1F1E6 + (cc[0] - 'A')
        val b = 0x1F1E6 + (cc[1] - 'A')
        return String(Character.toChars(a)) + String(Character.toChars(b))
    }

    fun line(
        phone: String,
        stops: List<HopStop>,
        unknown: String,
        timeout: String,
        star: String = "*",
        rtt: (Int) -> String = { "$it мс" },
    ): String {
        val rows = stops.mapIndexed { index, hop -> row(index + 1, hop, unknown, timeout, star, rtt) }
        return (listOf(phone) + rows).joinToString("\n")
    }

    fun row(
        index: Int,
        hop: HopStop,
        unknown: String,
        timeout: String,
        star: String,
        rtt: (Int) -> String,
    ): String {
        if (hop.synthetic) {
            val ip = hop.address?.takeIf { it.isNotBlank() } ?: return "$index  $star  $timeout"
            return answeredRow(index, ip, hop, unknown, timeout, rtt)
        }
        val ip = hop.address?.takeIf { it.isNotBlank() } ?: return "$index  $star  $timeout"
        return answeredRow(index, ip, hop, unknown, timeout, rtt)
    }

    private fun chipOf(stop: HopStop): HopChip {
        if (stop.timedOut || stop.address.isNullOrBlank()) return HopChip.Timeout
        val code = countryOrNull(stop.country) ?: return HopChip.Opaque
        return HopChip.Country(code)
    }

    private fun answeredRow(
        index: Int,
        ip: String,
        hop: HopStop,
        unknown: String,
        timeout: String,
        rtt: (Int) -> String,
    ): String {
        val who = hop.name?.trim()?.takeIf { it.isNotEmpty() && it != ip }?.let { "$it $ip" } ?: ip
        val facts = listOfNotNull(
            hop.rttMs?.let(rtt),
            countryOrNull(hop.country) ?: unknown.takeIf { !hop.synthetic },
            asnLabel(hop.asn, hop.org),
        )
        val tail = if (hop.timedOut) " → $timeout" else ""
        return "$index  $who  ${facts.joinToString(" · ")}$tail"
    }

    fun asnLabel(asn: Int?, org: String?): String? {
        val number = asn?.takeIf { it > 0 }?.let { "AS$it" }
        val name = org?.trim()?.takeIf { it.isNotEmpty() }
        if (number == null && name == null) return null
        return listOfNotNull(number, name).joinToString(" ")
    }
}

internal data class PingHop(val stop: HopStop, val terminal: Boolean)

internal fun parsePingHop(output: String): PingHop {
    val lower = output.lowercase()
    val ip = replyIp(output)
    val name = replyName(output, ip)
    val rtt = replyRtt(output)
    return when {
        ip != null && "time to live exceeded" in lower ->
            PingHop(HopStop(ip, country = null, timedOut = false, rttMs = rtt, name = name), terminal = false)
        ip != null && "unreachable" in lower ->
            PingHop(HopStop(ip, country = null, timedOut = false, rttMs = rtt, name = name), terminal = true)
        ip != null && "bytes from" in lower ->
            PingHop(HopStop(ip, country = null, timedOut = false, rttMs = rtt, name = name), terminal = true)
        else -> PingHop(HopStop(address = null, country = null, timedOut = true), terminal = false)
    }
}

private fun replyIp(output: String): String? {
    val named = Regex("(?i)(?:bytes from|from)\\s+\\S+\\s+\\((\\d{1,3}(?:\\.\\d{1,3}){3})\\)").find(output)
    val plain = Regex("(?i)(?:bytes from|from)\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})").find(output)
    val raw = named?.groupValues?.get(1) ?: plain?.groupValues?.get(1)
    return raw?.takeIf(::validIpv4)
}

private fun replyName(output: String, ip: String?): String? {
    if (ip == null) return null
    val match = Regex("(?i)(?:bytes from|from)\\s+(\\S+)\\s+\\(").find(output) ?: return null
    val token = match.groupValues[1]
    return token.takeIf { it.isNotEmpty() && !validIpv4(it) && it != ip }
}

private fun replyRtt(output: String): Int? {
    val match = Regex("(?i)time[=<]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms").find(output) ?: return null
    val value = match.groupValues[1].toFloatOrNull() ?: return null
    if (value <= 0f) return null
    val rounded = kotlin.math.round(value).toInt()
    return if (rounded == 0) 1 else rounded
}

private fun validIpv4(raw: String): Boolean {
    val parts = raw.split('.')
    if (parts.size != 4) return false
    return parts.all { octet -> octet.toIntOrNull() in 0..255 }
}

fun interface HopTracer {
    suspend fun trace(target: OutboundEndpoint, dialOk: Boolean): List<HopStop>

    /**
     * [dialOk] is sampled at the end of the walk (TCP may finish while ICMP is still walking).
     * Implementations must not block the caller’s dispatcher — hop probes stay on IO.
     */
    suspend fun traceLive(
        target: OutboundEndpoint,
        dialOk: () -> Boolean,
        onPartial: suspend (List<HopStop>) -> Unit,
    ): List<HopStop> {
        val stops = trace(target, dialOk())
        if (stops.isNotEmpty()) onPartial(stops)
        return stops
    }
}

/** Tests and the fallback: the dial target only, no extra round-trips. */
object DialOnlyHopTracer : HopTracer {
    override suspend fun trace(target: OutboundEndpoint, dialOk: Boolean): List<HopStop> =
        listOf(HopStop(address = target.host, country = null, timedOut = !dialOk, synthetic = dialOk))
}

/**
 * ICMP walk as a background map — Windows-tracert class:
 * continue past mid-path timeouts to destination / [HopPath.MAX_HOPS].
 * Each TTL is published as it returns. Never part of tunnel dial.
 */
class ShellPingTracer(
    private val ping: (ttl: Int, host: String) -> String = ::systemPing,
    private val maxHops: Int = HopPath.MAX_HOPS,
    private val annotate: (String) -> AsnFact? = { null },
    private val details: (String) -> HopNetworkDetails? = { null },
) : HopTracer {
    override suspend fun trace(target: OutboundEndpoint, dialOk: Boolean): List<HopStop> =
        traceLive(target, { dialOk }) {}

    override suspend fun traceLive(
        target: OutboundEndpoint,
        dialOk: () -> Boolean,
        onPartial: suspend (List<HopStop>) -> Unit,
    ): List<HopStop> = withContext(Dispatchers.IO) {
        if (!safeHost(target.host)) {
            val missed = fallback(target.host, dialOk())
            onPartial(missed)
            return@withContext missed
        }
        walkedStops(target.host, dialOk, onPartial)
    }

    private suspend fun walkedStops(
        host: String,
        dialOk: () -> Boolean,
        onPartial: suspend (List<HopStop>) -> Unit,
    ): List<HopStop> = coroutineScope {
        val stops = mutableListOf<HopStop>()
        val mutex = Mutex()
        val parallel = Semaphore(4)
        val detailJobs = mutableListOf<Job>()
        var ttl = 1
        var finished = false
        while (ttl <= maxHops && !finished) {
            coroutineContext.ensureActive()
            val step = readStep(ttl, host)
            val stop = if (step == null) timeoutStop() else enrich(step.stop)
            val position = stops.size
            mutex.withLock {
                stops += stop
                onPartial(stops.toList())
            }
            if (!stop.synthetic && !stop.timedOut && stop.address != null) {
                detailJobs += launch(Dispatchers.IO) {
                    parallel.withPermit {
                        val found = runCatching { details(stop.address) }.getOrNull() ?: return@withPermit
                        mutex.withLock {
                            stops[position] = stops[position].copy(
                                asn = found.asn ?: stops[position].asn,
                                org = found.holder ?: stops[position].org,
                                prefix = found.prefix ?: stops[position].prefix,
                                name = found.ptr ?: stops[position].name,
                                registeredTo = found.registeredTo ?: stops[position].registeredTo,
                            )
                            onPartial(stops.toList())
                        }
                    }
                }
            }
            finished = step?.terminal == true
            ttl += 1
        }
        detailJobs.joinAll()
        if (stops.isEmpty()) return@coroutineScope fallback(host, dialOk()).also { onPartial(it) }
        if (dialOk() && !finished) {
            val withDest = stops + fallback(host, dialOk = true)
            onPartial(withDest)
            return@coroutineScope withDest
        }
        stops
    }

    private fun readStep(ttl: Int, host: String): PingHop? {
        val output = readPing(ttl, host) ?: return null
        return parsePingHop(output)
    }

    private fun timeoutStop(): HopStop = HopStop(address = null, country = null, timedOut = true)

    private fun enrich(stop: HopStop): HopStop {
        val ip = stop.address ?: return stop
        val fact = readFact(ip) ?: return stop
        return stop.copy(
            country = HopPath.countryOrNull(fact.country) ?: HopPath.countryOrNull(stop.country),
            asn = fact.asn?.takeIf { it > 0 },
            org = fact.org?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun readFact(ip: String): AsnFact? = try {
        annotate(ip)
    } catch (error: IOException) {
        LerNetLog.w(TAG, "asn lookup failed: ${error.message}", error)
        null
    }

    private fun readPing(ttl: Int, host: String): String? = try {
        ping(ttl, host)
    } catch (error: IOException) {
        LerNetLog.w(TAG, "ping ttl=$ttl failed: ${error.message}", error)
        null
    }

    private fun fallback(host: String, dialOk: Boolean): List<HopStop> {
        val base = HopStop(address = host, country = null, timedOut = !dialOk, synthetic = dialOk)
        return listOf(enrich(base))
    }
}

private const val TAG = "HopPath"

private fun safeHost(host: String): Boolean =
    host.isNotBlank() && host.length <= 253 && !host.startsWith("-") && host.none { it.isWhitespace() }

private fun systemPing(ttl: Int, host: String): String {
    val wait = HopPath.PING_WAIT_SEC.toString()
    val process = ProcessBuilder(
        "/system/bin/ping",
        "-n",
        "-c",
        "1",
        "-W",
        wait,
        "-t",
        ttl.toString(),
        host,
    )
        .redirectErrorStream(true)
        .start()
    val ceiling = HopPath.PING_WAIT_SEC + 2L
    if (!process.waitFor(ceiling, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return ""
    }
    return process.inputStream.bufferedReader().use { it.readText() }
}
