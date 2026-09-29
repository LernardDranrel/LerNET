package app.lernet.desktop.observation

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class ObservationCommandResult(val exitCode: Int?, val output: String, val timedOut: Boolean = false)

internal fun interface ObservationCommandRunner {
    fun run(arguments: List<String>, timeoutMs: Long): ObservationCommandResult
}

/** Only the process created here is terminated. Output is bounded independently of a noisy provider. */
internal object SystemObservationCommandRunner : ObservationCommandRunner {
    override fun run(arguments: List<String>, timeoutMs: Long): ObservationCommandResult {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val bytes = ByteArrayOutputStream()
        val reader = Thread({
            runCatching {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(bytes) { if (bytes.size() < 4 * 1024 * 1024) bytes.write(buffer, 0, minOf(count, 4 * 1024 * 1024 - bytes.size())) }
                    }
                }
            }
        }, "LerNET-observation-output").apply { isDaemon = true; start() }
        var completed = false
        try {
            completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) destroyOwnedProcess(process)
            reader.join(1000)
            val output = synchronized(bytes) { bytes.toString(StandardCharsets.UTF_8) }.trimStart('\uFEFF')
            return ObservationCommandResult(if (completed) process.exitValue() else null, output, !completed)
        } finally {
            destroyOwnedProcess(process)
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            if (!completed) reader.interrupt()
        }
    }
    private fun destroyOwnedProcess(process: Process) {
        process.descendants().use { children -> children.forEach { child -> if (child.isAlive) child.destroyForcibly() } }
        if (process.isAlive) process.destroyForcibly()
    }
}

internal fun windowsTool(name: String): String = java.io.File(
    System.getenv("SystemRoot") ?: "C:\\Windows", "System32/$name",
).absolutePath

internal fun powershellArguments(script: String): List<String> = listOf(
    windowsTool("WindowsPowerShell/v1.0/powershell.exe"), "-NoProfile", "-NonInteractive", "-EncodedCommand",
    Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE)),
)
