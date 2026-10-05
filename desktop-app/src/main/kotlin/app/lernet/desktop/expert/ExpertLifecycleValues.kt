package app.lernet.desktop.expert

import java.math.BigDecimal

/** A duration round-trips imported millisecond precision and uses the shared contract's bounds. */
internal object ExpertLifecycleValues {
    fun seconds(milliseconds: Long): String = BigDecimal.valueOf(milliseconds, 3).stripTrailingZeros().toPlainString()

    fun milliseconds(seconds: String, maximum: Long): Long? {
        val normalized = seconds.trim().replace(',', '.')
        if (!normalized.matches(Regex("[0-9]{1,6}(?:\\.[0-9]{1,3})?"))) return null
        val value = normalized.toBigDecimalOrNull()?.movePointRight(3) ?: return null
        if (value < BigDecimal.valueOf(1_000) || value > BigDecimal.valueOf(maximum)) return null
        return value.toLong()
    }
}
