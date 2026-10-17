package app.lernet.engine.net

import kotlin.random.Random

/**
 * Liveness check for QUIC-based configs (hysteria2, tuic) without their keys: a QUIC long-header
 * packet with a reserved version (RFC 9000 §15, 0x?a?a?a?a) must be answered by any QUIC server
 * with a Version Negotiation packet (§6). A TCP connect never works for these servers.
 * It proves the server is reachable and speaks QUIC, not that the tunnel works.
 */
object QuicVersionProbe {
    const val PACKET_SIZE = 1_200
    private const val CID_LENGTH = 8

    fun request(random: Random = Random.Default): ByteArray {
        val packet = random.nextBytes(PACKET_SIZE)
        packet[0] = (0xC0 or (random.nextInt() and 0x0F)).toByte()
        // Reserved "force version negotiation" pattern.
        val reserved = byteArrayOf(0x1a, 0x2a, 0x3a, 0x4a)
        System.arraycopy(reserved, 0, packet, 1, 4)
        packet[5] = CID_LENGTH.toByte()
        packet[6 + CID_LENGTH] = CID_LENGTH.toByte()
        return packet
    }

    /** True when [reply] is a Version Negotiation packet (long header, version 0). */
    fun isVersionNegotiation(reply: ByteArray, length: Int = reply.size): Boolean =
        length >= 7 && (reply[0].toInt() and 0x80) != 0 &&
            reply[1] == 0.toByte() && reply[2] == 0.toByte() && reply[3] == 0.toByte() && reply[4] == 0.toByte()
}
