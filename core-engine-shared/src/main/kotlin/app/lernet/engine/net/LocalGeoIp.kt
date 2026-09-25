package app.lernet.engine.net

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress

/** Reverse lookup built from the same bundled SagerNet country sets used by routing. */
class LocalGeoIp(private val openIndex: () -> InputStream) {
    private val index: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        openIndex().use { it.readBytes() }.also(::validate)
    }

    /** Only IP literals are accepted; this method never resolves a hostname. */
    fun country(ip: String): String? {
        if (IpScope.isSkipped(ip) && !ip.contains(':')) return null
        val address = literal(ip) ?: return null
        val bytes = index
        val v4Count = number(bytes, 4)
        val v6Count = number(bytes, 8)
        val size = address.size
        val count = if (size == IPV4_BYTES) v4Count else v6Count
        val recordSize = size * 2 + 2
        val base = HEADER_BYTES + if (size == IPV4_BYTES) 0 else v4Count * IPV4_RECORD
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val offset = base + middle * recordSize
            when {
                compare(address, bytes, offset, size) < 0 -> high = middle - 1
                compare(address, bytes, offset + size, size) > 0 -> low = middle + 1
                else -> return String(bytes, offset + size * 2, 2, Charsets.US_ASCII)
            }
        }
        return null
    }

    private fun literal(value: String): ByteArray? {
        if (value.contains(':')) {
            if (value.any { it !in "0123456789abcdefABCDEF:." }) return null
            return runCatching { InetAddress.getByName(value).address }
                .getOrNull()?.takeIf { it.size == IPV6_BYTES }
        }
        val parts = value.split('.')
        if (parts.size != IPV4_BYTES) return null
        val octets = parts.map { part ->
            part.toIntOrNull()?.takeIf { it in 0..255 && part == it.toString() }
        }
        if (octets.any { it == null }) return null
        return octets.map { it!!.toByte() }.toByteArray()
    }

    private fun validate(bytes: ByteArray) {
        if (bytes.size < HEADER_BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw IOException("Invalid hop GeoIP index")
        }
        val v4 = number(bytes, 4).toLong()
        val v6 = number(bytes, 8).toLong()
        if (v4 < 0 || v6 < 0 || HEADER_BYTES + v4 * IPV4_RECORD + v6 * IPV6_RECORD != bytes.size.toLong()) {
            throw IOException("Invalid hop GeoIP index length")
        }
    }

    private fun number(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun compare(address: ByteArray, bytes: ByteArray, offset: Int, length: Int): Int {
        for (position in 0 until length) {
            val difference = (address[position].toInt() and 0xff) - (bytes[offset + position].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return 0
    }

    private companion object {
        val MAGIC = byteArrayOf('H'.code.toByte(), 'G'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())
        const val HEADER_BYTES = 12
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
        const val IPV4_RECORD = 10
        const val IPV6_RECORD = 34
    }
}
