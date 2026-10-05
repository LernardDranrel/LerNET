package app.lernet.ui.expert

import java.math.BigDecimal

/** Seconds with millisecond precision; never round an imported lifecycle value. */
internal fun expertSecondsInput(milliseconds: Long): String =
    BigDecimal.valueOf(milliseconds, 3).stripTrailingZeros().toPlainString()

internal fun expertIdleMilliseconds(input: String): Long? = runCatching {
    val milliseconds = input.trim().replace(',', '.').toBigDecimal().movePointRight(3).longValueExact()
    milliseconds.takeIf { it in 1_000..86_400_000 }
}.getOrNull()

internal fun expertIdleAfterEdit(initialMs: Long, cold: Boolean, input: String): Long? =
    if (cold) expertIdleMilliseconds(input) else initialMs
