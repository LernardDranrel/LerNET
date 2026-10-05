package app.lernet.routing.policy

import java.net.IDN
import java.net.InetAddress

/** Accept a host, not a URL, host:port pair, scoped interface address or socket authority. */
object PolicyDestinationAddress {
    fun normalize(value: String): String? {
        if (value.isBlank() ||
            value.any { it.isWhitespace() || it.isISOControl() } ||
            value.any { it in "/@[]%\\?#" }
        ) {
            return null
        }
        if (':' in value) {
            if (value.any { it !in "0123456789abcdefABCDEF:." }) return null
            // A colon and the restricted alphabet ensure this parses a literal, never a DNS lookup.
            return runCatching { InetAddress.getByName(value).hostAddress }.getOrNull()
        }
        if (value.all { it.isDigit() || it == '.' }) {
            val octets = value.split('.')
            if (octets.size != 4 ||
                octets.any { it.isEmpty() || it.length > 3 || it.toIntOrNull()?.let { number -> number in 0..255 } != true }
            ) {
                return null
            }
            return octets.joinToString(".") { it.toInt().toString() }
        }
        val ascii = runCatching { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: return null
        val host = ascii.removeSuffix(".")
        if (host.length !in 1..253 ||
            host.split('.').any { label ->
                label.length !in 1..63 ||
                    label.first() == '-' ||
                    label.last() == '-' ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }
        ) {
            return null
        }
        return ascii
    }
}
