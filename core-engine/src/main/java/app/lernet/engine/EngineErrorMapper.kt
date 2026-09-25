package app.lernet.engine

object EngineErrorMapper {
    fun map(raw: String): ConnectionCause {
        val detail = raw.trim().ifBlank { "unknown" }
        val lower = detail.lowercase()
        return when {
            isPermission(lower) -> ConnectionCause.VpnPermissionDenied
            isRevoked(lower) -> ConnectionCause.ServiceRevoked
            isConfig(lower) -> ConnectionCause.InvalidConfig(listOf(detail))
            isTls(lower) -> ConnectionCause.TlsFailure(detail)
            isHandshake(lower) -> ConnectionCause.HandshakeFailure(detail)
            isReset(lower) -> ConnectionCause.ConnectionReset(detail)
            isTimeout(lower) -> ConnectionCause.DialTimeout(extractTimeoutSeconds(lower))
            isDial(lower) -> ConnectionCause.DialFailure(detail)
            else -> ConnectionCause.EngineStartFailed(detail)
        }
    }

    private fun isConfig(lower: String): Boolean =
        "decode config" in lower ||
            "unknown transport" in lower ||
            "parse config" in lower ||
            "invalid config" in lower ||
            "check config" in lower ||
            "legacy dns" in lower ||
            "legacy inbound" in lower ||
            "legacy special outbound" in lower

    private fun isPermission(lower: String): Boolean =
        "missing vpn permission" in lower || "need permission" in lower

    private fun isRevoked(lower: String): Boolean =
        "revoked" in lower ||
            "vpn service destroyed" in lower ||
            "not prepared" in lower ||
            "protect returned false" in lower ||
            "protect=false" in lower ||
            "missing in tun" in lower ||
            ("protect(" in lower && "failed → failed" in lower)

    private fun isTls(lower: String): Boolean =
        "tls" in lower || "certificate" in lower || "x509" in lower || "ssl" in lower

    private fun isHandshake(lower: String): Boolean = "handshake" in lower

    private fun isReset(lower: String): Boolean =
        "connection reset" in lower || "broken pipe" in lower || "eof" in lower

    private fun isTimeout(lower: String): Boolean =
        "timeout" in lower || "i/o deadline" in lower || "deadline exceeded" in lower

    private fun isDial(lower: String): Boolean =
        "dial" in lower || "connect:" in lower || "network is unreachable" in lower || "no route" in lower

    private fun extractTimeoutSeconds(lower: String): Int {
        val match = Regex("""(\d+)\s*s""").find(lower)
        return match?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 120) ?: 10
    }
}
