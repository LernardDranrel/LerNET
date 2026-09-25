package app.lernet.engine.log

object CrashReport {
    fun renderLast(
        timestamp: String,
        threadName: String,
        stack: String,
        crumb: String,
    ): String =
        buildString {
            appendLine("=== CRASH-LAST ===")
            appendLine("ts=$timestamp")
            appendLine("thread=$threadName")
            appendLine()
            appendLine(stack.trimEnd())
            appendLine()
            appendLine("=== LAST CRUMB ===")
            appendLine(crumb.ifBlank { "no crumb" })
        }

    fun render(
        threadName: String,
        stack: String,
        logs: String,
    ): String =
        buildString {
            appendLine("=== CRASH ===")
            appendLine("thread=$threadName")
            appendLine()
            appendLine(stack.trimEnd())
            appendLine()
            appendLine("=== LOGS ===")
            append(logs)
            if (logs.isNotEmpty() && !logs.endsWith("\n")) {
                appendLine()
            }
        }
}
