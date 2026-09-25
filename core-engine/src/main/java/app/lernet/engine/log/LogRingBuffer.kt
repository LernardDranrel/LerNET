package app.lernet.engine.log

import java.time.Instant

class LogRingBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val now: () -> String = { Instant.now().toString() },
) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var bytes: Int = 0

    fun append(level: String, tag: String, message: String) {
        val line = "${now()} $level $tag $message"
        synchronized(lock) {
            lines.addLast(line)
            bytes += utf8Size(line)
            evictLocked()
        }
    }

    fun snapshot(): String =
        synchronized(lock) {
            lines.joinToString(separator = "\n")
        }

    fun lineCount(): Int = synchronized(lock) { lines.size }

    private fun evictLocked() {
        while (lines.isNotEmpty() && (lines.size > maxLines || bytes > maxBytes)) {
            val removed = lines.removeFirst()
            bytes -= utf8Size(removed)
            if (bytes < 0) bytes = 0
        }
    }

    private fun utf8Size(line: String): Int = line.toByteArray(Charsets.UTF_8).size + 1

    companion object {
        const val DEFAULT_MAX_LINES = 4_000
        const val DEFAULT_MAX_BYTES = 1024 * 1024
    }
}
