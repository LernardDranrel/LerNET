package app.lernet.engine.policy

data class ReconnectSettings(
    val maxAttempts: Int = 5,
    val initialBackoffMs: Long = 1_000L,
    val backoffCapMs: Long = 30_000L,
    val watchdogTimeoutMs: Long = 20_000L,
    val connectTimeoutMs: Long = 45_000L,
) {
    fun backoffMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val raw = initialBackoffMs * (1L shl (safeAttempt - 1).coerceAtMost(16))
        return raw.coerceAtMost(backoffCapMs)
    }

    /** First miss uses [watchdogTimeoutMs]; later windows double up to [backoffCapMs]. */
    fun watchdogWindowMs(misses: Int): Long {
        val raw = watchdogTimeoutMs * (1L shl misses.coerceIn(0, 8))
        return raw.coerceAtMost(backoffCapMs.coerceAtLeast(watchdogTimeoutMs))
    }
}

data class FailoverSettings(
    val enabled: Boolean = false,
    val groupId: String? = null,
)

data class ManualFailoverGroup(
    val id: String,
    val name: String,
    val outboundIds: List<String>,
    val labels: Map<String, String> = emptyMap(),
)
