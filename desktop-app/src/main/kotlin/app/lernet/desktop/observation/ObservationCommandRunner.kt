package app.lernet.desktop.observation

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class ObservationCommandResult(
    val exitCode: Int?, val output: String, val timedOut: Boolean = false,
    val errorOutput: String = "", val outputTruncated: Boolean = false, val readError: String? = null,
)

internal fun interface ObservationCommandRunner {
    fun run(arguments: List<String>, timeoutMs: Long): ObservationCommandResult
}

/** Only the process created here is terminated. Output is bounded independently of a noisy provider. */
internal object SystemObservationCommandRunner : ObservationCommandRunner {
    override fun run(arguments: List<String>, timeoutMs: Long): ObservationCommandResult {
        // PowerShell writes CLIXML diagnostics/progress to stderr, even on a successful exit.
        // Mixing them into stdout corrupts an otherwise valid JSON envelope.
        val process = ProcessBuilder(arguments).start()
        val stdout = BoundedCommandOutput(process.inputStream, 4 * 1024 * 1024)
        val stderr = BoundedCommandOutput(process.errorStream, 64 * 1024)
        var completed = false
        try {
            completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) destroyOwnedProcess(process)
            stdout.await()
            stderr.await()
            return ObservationCommandResult(if (completed) process.exitValue() else null, stdout.text(), !completed,
                errorOutput = stderr.text(), outputTruncated = stdout.truncated, readError = stdout.readError)
        } finally {
            destroyOwnedProcess(process)
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            stdout.close()
            stderr.close()
        }
    }
    private fun destroyOwnedProcess(process: Process) {
        process.descendants().use { children -> children.forEach { child -> if (child.isAlive) child.destroyForcibly() } }
        if (process.isAlive) process.destroyForcibly()
    }
}

private class BoundedCommandOutput(private val stream: java.io.InputStream, private val limit: Int) {
    private val bytes = ByteArrayOutputStream()
    @Volatile var truncated = false
        private set
    @Volatile var readError: String? = null
        private set
    private val reader = Thread({
        try {
            stream.use {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    synchronized(bytes) {
                        val retained = minOf(count, limit - bytes.size())
                        bytes.write(buffer, 0, retained)
                        if (retained < count) truncated = true
                    }
                }
            }
        } catch (error: Exception) { readError = error.javaClass.simpleName }
    }, "LerNET-observation-output").apply { isDaemon = true; start() }

    fun await() {
        reader.join(1000)
        if (reader.isAlive) readError = "Поток ответа не завершился"
    }
    fun text(): String = synchronized(bytes) { bytes.toString(StandardCharsets.UTF_8) }.trimStart('\uFEFF')
    fun close() { runCatching { stream.close() }; if (reader.isAlive) reader.interrupt() }
}

/** Do not include the response body in errors: XML/events may contain private device data. */
internal fun observationOutputProblem(result: ObservationCommandResult): String? = when {
    result.outputTruncated -> "Ответ источника превышает лимит 4 МБ и обрезан. JSON не разбирался; данные источника неполные."
    result.readError != null -> "Не удалось полностью прочитать ответ источника: ${result.readError}"
    result.exitCode != 0 -> "Источник завершился с кодом ${result.exitCode ?: "неизвестен"}." +
        if (result.errorOutput.isNotBlank()) " PowerShell передала диагностику в stderr." else ""
    else -> null
}

internal fun observationJsonProblem(error: Exception): String {
    val offset = Regex("(?:offset|position)\\s+(\\d+)").find(error.message.orEmpty())?.groupValues?.get(1)
    return "Не удалось разобрать JSON источника: ${error.javaClass.simpleName}" +
        (offset?.let { ", позиция $it" } ?: "") + ". Содержимое ответа не включено в ошибку."
}

internal fun windowsTool(name: String): String = java.io.File(
    System.getenv("SystemRoot") ?: "C:\\Windows", "System32/$name",
).absolutePath

internal fun powershellArguments(script: String): List<String> = listOf(
    windowsTool("WindowsPowerShell/v1.0/powershell.exe"), "-NoProfile", "-NonInteractive", "-EncodedCommand",
    Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE)),
)
