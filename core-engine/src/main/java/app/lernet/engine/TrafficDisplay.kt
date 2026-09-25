package app.lernet.engine

data class TrafficView(
    val uplinkRate: String,
    val downlinkRate: String,
    val uplinkTotal: String,
    val downlinkTotal: String,
    val showTotals: Boolean,
    val dnsHint: Boolean,
    val impliesDead: Boolean,
    val primary: String,
)

object TrafficDisplay {
    fun format(
        uplinkBps: Long,
        downlinkBps: Long,
        uplinkTotal: Long,
        downlinkTotal: Long,
        dnsOk: Boolean,
    ): TrafficView {
        val upRate = formatRate(uplinkBps)
        val downRate = formatRate(downlinkBps)
        val upTotal = formatBytes(uplinkTotal)
        val downTotal = formatBytes(downlinkTotal)
        val showTotals = uplinkTotal > 0L || downlinkTotal > 0L
        val ratesZero = uplinkBps <= 0L && downlinkBps <= 0L
        val dnsHint = dnsOk && !showTotals && ratesZero
        val impliesDead = ratesZero && !showTotals && !dnsOk
        val rates = "↑ $upRate ↓ $downRate"
        val primary = when {
            showTotals -> "$rates · ↑ $upTotal ↓ $downTotal"
            dnsHint -> "$rates · DNS"
            else -> rates
        }
        return TrafficView(
            uplinkRate = upRate,
            downlinkRate = downRate,
            uplinkTotal = upTotal,
            downlinkTotal = downTotal,
            showTotals = showTotals,
            dnsHint = dnsHint,
            impliesDead = impliesDead,
            primary = primary,
        )
    }

    fun formatRate(bytesPerSecond: Long): String = "${formatMagnitude(bytesPerSecond)}/s"

    fun formatBytes(bytes: Long): String = formatMagnitude(bytes)

    private fun formatMagnitude(bytes: Long): String {
        val value = bytes.coerceAtLeast(0)
        return when {
            value < 1024L -> "$value B"
            value < 1024L * 1024L -> "${value / 1024L} KiB"
            else -> "${value / (1024L * 1024L)} MiB"
        }
    }
}
