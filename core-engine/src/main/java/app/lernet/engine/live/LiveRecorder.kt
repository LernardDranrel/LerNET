package app.lernet.engine.live

import app.lernet.config.redact.SecretRedactor

class LiveRecorder(
    private val maxLines: Int = DEFAULT_MAX_LINES,
) {
    private val lock = Any()
    var recording: Boolean = false
        private set
    private val lines = ArrayDeque<String>()

    fun setRecording(on: Boolean) {
        synchronized(lock) { recording = on }
    }

    fun apply(reset: Boolean, rows: List<LiveConn>) {
        synchronized(lock) {
            if (!recording) return
            if (reset) {
                lines.clear()
            }
            rows.forEach { row ->
                lines.addLast(SecretRedactor.redact(LiveConnFormat.line(row)))
                while (lines.size > maxLines) {
                    lines.removeFirst()
                }
            }
        }
    }

    fun exportText(): String = synchronized(lock) { lines.joinToString(separator = "\n") }

    companion object {
        const val DEFAULT_MAX_LINES = 2_000
    }
}
