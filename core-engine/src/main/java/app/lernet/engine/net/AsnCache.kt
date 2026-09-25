package app.lernet.engine.net

import app.lernet.engine.redact.LerNetLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** A registry fact. Null fields are omitted, never replaced with a guessed name. */
data class AsnFact(
    val country: String?,
    val asn: Int?,
    val org: String?,
)

fun interface AsnLookup {
    fun lookup(ip: String): AsnFact?
}

/**
 * Team Cymru DNS (origin + AS name). A miss stores nothing, so the next
 * Connect or manual refresh can try again. A hit is reused for a day.
 */
internal class DailyAsnCache(
    private val nowMs: () -> Long,
    private val fetch: (String) -> AsnFact?,
    private val ttlMs: Long = DAY_MS,
) : AsnLookup {
    private val rows = HashMap<String, Cached>()

    override fun lookup(ip: String): AsnFact? {
        if (CymruAsn.isSkipped(ip)) return null
        val now = nowMs()
        val hit = synchronized(rows) { rows[ip] }
        if (hit != null && now - hit.atMs < ttlMs) return hit.fact
        val fact = fetch(ip) ?: return null
        synchronized(rows) { rows[ip] = Cached(now, fact) }
        return fact
    }

    private data class Cached(val atMs: Long, val fact: AsnFact)

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

internal object CymruAsn {
    fun lookup(ip: String, query: (String) -> String?): AsnFact? {
        if (isSkipped(ip)) return null
        val origin = query(originName(ip)) ?: return null
        val parsed = parseOrigin(origin) ?: return null
        return AsnFact(parsed.country, parsed.asn, parseOrg(query(nameName(parsed.asn))))
    }

    fun isSkipped(ip: String): Boolean {
        val octets = ipv4Octets(ip) ?: return true
        return notRoutable(octets[0], octets[1])
    }

    fun parseOrigin(raw: String): Origin? {
        val fields = fields(raw)
        val asn = fields.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Origin(asn, HopPath.countryOrNull(fields.getOrNull(2)))
    }

    fun parseOrg(raw: String?): String? {
        val name = fields(raw.orEmpty()).getOrNull(ORG_FIELD)?.trim().orEmpty()
        return name.takeIf { it.isNotEmpty() }
    }

    private fun ipv4Octets(ip: String): IntArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val parsed = parts.map { part -> part.toIntOrNull()?.takeIf { octet -> octet in 0..255 } }
        if (parsed.any { it == null }) return null
        return parsed.filterNotNull().toIntArray()
    }

    private fun notRoutable(first: Int, second: Int): Boolean =
        tenLoopOrMulticast(first) || rfc1918(first, second) || cgnat(first, second)

    private fun tenLoopOrMulticast(first: Int): Boolean = first == 10 || first == 127 || first >= 224

    private fun rfc1918(first: Int, second: Int): Boolean =
        (first == 172 && second in 16..31) || (first == 192 && second == 168)

    private fun cgnat(first: Int, second: Int): Boolean = first == 100 && second in 64..127

    private fun originName(ip: String): String =
        ip.split('.').asReversed().joinToString(".") + ".origin.asn.cymru.com"

    private fun nameName(asn: Int): String = "AS$asn.asn.cymru.com"

    private fun fields(raw: String): List<String> =
        raw.trim().trim('"').split('|').map { it.trim() }

    internal data class Origin(val asn: Int, val country: String?)

    private const val ORG_FIELD = 4
}

object AsnCaches {
    var protect: (DatagramSocket) -> Boolean = { true }

    val shared: AsnLookup = DailyAsnCache(
        nowMs = System::currentTimeMillis,
        fetch = { ip -> CymruAsn.lookup(ip) { name -> DnsTxt.query(name, protect) } },
    )
}

internal object DnsTxt {
    @Volatile private var retryAfterMs: Long = 0

    fun query(name: String, protect: (DatagramSocket) -> Boolean): String? {
        if (System.currentTimeMillis() < retryAfterMs) return null
        val socket = DatagramSocket()
        try {
            if (!protect(socket)) return null
            socket.soTimeout = TIMEOUT_MS
            val packet = encode(name)
            val address = InetAddress.getByName(RESOLVER)
            socket.send(DatagramPacket(packet, packet.size, address, PORT))
            val buffer = ByteArray(MAX_PACKET)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return decode(buffer, response.length)
        } catch (error: IOException) {
            if (error is SocketTimeoutException) {
                // One blocked DNS resolver should not delay every hop in the ICMP walk.
                retryAfterMs = System.currentTimeMillis() + 60_000
            }
            LerNetLog.w(TAG, "txt lookup failed: ${error.message}", error)
            return null
        } finally {
            socket.close()
        }
    }

    fun encode(name: String, id: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8)
        out.write(id and 0xff)
        out.write(0x01)
        out.write(0x00)
        repeat(4) { out.write(0) }
        out.write(0)
        out.write(1)
        name.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(0)
        out.write(TXT)
        out.write(0)
        out.write(1)
        return out.toByteArray()
    }

    fun decode(packet: ByteArray, length: Int): String? {
        val answer = answerRdata(packet, length) ?: return null
        return readTxt(packet, answer.first, answer.second)
    }

    private fun answerRdata(packet: ByteArray, length: Int): Pair<Int, Int>? {
        if (length < HEADER || packet.size < length || u16(packet, ANCOUNT) <= 0) return null
        val afterName = locateAnswer(packet, length) ?: return null
        val type = u16(packet, afterName)
        val rdlen = u16(packet, afterName + RD_OFFSET)
        val start = afterName + RDATA
        if (type != TXT || start + rdlen > length) return null
        return start to rdlen
    }

    private fun locateAnswer(packet: ByteArray, length: Int): Int? {
        val afterQuestion = skipQuestion(packet, length) ?: return null
        return skipName(packet, length, afterQuestion)
    }

    private fun skipQuestion(packet: ByteArray, length: Int): Int? {
        val name = skipName(packet, length, HEADER) ?: return null
        val end = name + QTYPE_QCLASS
        return if (end <= length) end else null
    }

    private fun skipName(packet: ByteArray, length: Int, start: Int): Int? {
        var index = start
        var hops = 0
        var done: Int? = null
        while (done == null && index < length && hops < NAME_HOPS) {
            val len = packet[index].toInt() and 0xff
            when {
                len == 0 -> done = index + 1
                len and COMPRESSION == COMPRESSION -> done = if (index + 1 < length) index + 2 else INVALID
                index + 1 + len > length -> done = INVALID
                else -> {
                    index += 1 + len
                    hops += 1
                }
            }
        }
        return done?.takeIf { it >= 0 }
    }

    private fun readTxt(packet: ByteArray, start: Int, rdlen: Int): String? {
        val end = start + rdlen
        var index = start
        val text = StringBuilder()
        while (index < end) {
            val len = packet[index].toInt() and 0xff
            val next = index + 1 + len
            if (next > end) return null
            text.append(String(packet, index + 1, len, Charsets.UTF_8))
            index = next
        }
        return text.toString().trim().trim('"').takeIf { it.isNotEmpty() }
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

    private const val TAG = "DnsTxt"
    private const val RESOLVER = "1.1.1.1"
    private const val PORT = 53
    private const val TIMEOUT_MS = 1_500
    private const val MAX_PACKET = 512
    private const val HEADER = 12
    private const val ANCOUNT = 6
    private const val TXT = 16
    private const val QTYPE_QCLASS = 4
    private const val RD_OFFSET = 8
    private const val RDATA = 10
    private const val NAME_HOPS = 16
    private const val COMPRESSION = 0xC0
    private const val INVALID = -1
}
