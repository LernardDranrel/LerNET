package app.lernet.engine.policy

/** One config tried during a folder failover, with the plain reason it was not used. */
data class FailoverAttempt(val name: String, val reason: String, val atMs: Long)

/**
 * Honest «all configs are down» report (mockup 10): which configs were tried, why, and when the
 * next attempt is. The tunnel stays up meanwhile, so traffic does not leak. Same on Android and Windows.
 */
data class FailoverReport(val groupName: String?, val attempts: List<FailoverAttempt>, val nextRetry: String?) {
    companion object {
        const val MAX_ROWS = 8

        /** Latest result per config, so a repeated pass updates rows instead of growing the list. */
        fun merge(previous: FailoverReport?, groupName: String?, fresh: List<FailoverAttempt>, nextRetry: String?): FailoverReport {
            val old = previous?.takeIf { it.groupName == groupName }?.attempts.orEmpty()
            val names = fresh.map { it.name }.toSet()
            return FailoverReport(groupName, (old.filter { it.name !in names } + fresh).takeLast(MAX_ROWS), nextRetry)
        }
    }
}
