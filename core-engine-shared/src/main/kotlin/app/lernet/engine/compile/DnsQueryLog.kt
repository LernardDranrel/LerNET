package app.lernet.engine.compile

data class DnsQueryRecord(
    val domain: String,
    val queryType: Int,
    val failed: Boolean,
    val error: String?,
    val rcode: Int,
    val answerCount: Int,
    val serverType: String?,
    val server: String?,
)

/**
 * Structured line for libbox [CommandDNS] queries. Success = exchange finished
 * without error (NXDOMAIN still ok, answers may be 0). Failure = failed flag
 * or error string.
 */
object DnsQueryLog {
    fun format(record: DnsQueryRecord): String {
        val host = record.domain.ifBlank { "?" }
        val kind = record.serverType.orEmpty().ifBlank { "?" }
        val target = record.server.orEmpty().ifBlank { "?" }
        return if (record.failed || !record.error.isNullOrBlank()) {
            "dns query fail domain=$host type=${record.queryType} rcode=${record.rcode} " +
                "error=${record.error ?: "failed"} serverType=$kind server=$target answers=${record.answerCount}"
        } else {
            "dns query ok domain=$host type=${record.queryType} rcode=${record.rcode} " +
                "answers=${record.answerCount} serverType=$kind server=$target"
        }
    }
}
