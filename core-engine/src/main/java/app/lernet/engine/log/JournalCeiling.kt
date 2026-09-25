package app.lernet.engine.log

object JournalCeiling {
    const val MIN_MB = 2
    const val MAX_MB = 100
    const val DEFAULT_MB = 100

    fun mb(raw: Int): Int = raw.coerceIn(MIN_MB, MAX_MB)

    fun bytes(rawMb: Int): Int = mb(rawMb) * 1024 * 1024
}
