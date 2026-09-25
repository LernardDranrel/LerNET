package app.lernet.engine.log

/**
 * In-memory tail of engine/libbox lines for share after a mid-session death.
 */
class EngineLogWindow(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val lines = ArrayDeque<Pair<Long, String>>()

    fun add(line: String) {
        val at = nowMs()
        synchronized(lock) {
            lines.addLast(at to line)
            evictLocked(at)
        }
    }

    fun snapshot(): String {
        val at = nowMs()
        return synchronized(lock) {
            evictLocked(at)
            lines.joinToString(separator = "\n") { it.second }
        }
    }

    private fun evictLocked(now: Long) {
        val floor = now - windowMs
        while (lines.isNotEmpty() && lines.first().first < floor) {
            lines.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5 * 60_000L
    }
}
