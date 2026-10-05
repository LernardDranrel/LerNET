package app.lernet.desktop.protection

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val PROTECTION_CONTROL_BUDGET_NANOS = 8_000_000_000L
private const val TERMINATION_RESERVE_NANOS = 1_000_000_000L
internal const val PROTECTION_CONTROL_OUTPUT_LIMIT = 16_384

internal data class ProtectionControlOutput(val exitCode: Int, val text: String)

internal fun protectionControlDeadline(): Long = System.nanoTime() + PROTECTION_CONTROL_BUDGET_NANOS

/** Killing an IPC client cannot roll back a request already accepted by the guardian. */
internal fun runProtectionControl(process: Process, deadlineNanos: Long): ProtectionControlOutput {
    val output = CompletableFuture<ByteArray>()
    Thread({
        try {
            val bytes = process.inputStream.use { it.readNBytes(PROTECTION_CONTROL_OUTPUT_LIMIT + 1) }
            check(bytes.size <= PROTECTION_CONTROL_OUTPUT_LIMIT) { "Ответ помощника защиты превышает допустимый размер" }
            output.complete(bytes)
        } catch (failure: Throwable) {
            output.completeExceptionally(failure)
        }
    }, "LerNET-protection-response").apply {
        isDaemon = true
        start()
    }

    val workDeadline = deadlineNanos - TERMINATION_RESERVE_NANOS
    fun remainingWork(): Long = workDeadline - System.nanoTime()
    try {
        while (process.isAlive) {
            if (output.isCompletedExceptionally) output.join()
            val remaining = remainingWork()
            if (remaining <= 0L) throw TimeoutException("guardian command deadline")
            process.waitFor(minOf(remaining, 50_000_000L), TimeUnit.NANOSECONDS)
        }
        val remaining = remainingWork()
        if (remaining <= 0L) throw TimeoutException("guardian response deadline")
        val bytes = output.get(remaining, TimeUnit.NANOSECONDS)
        return ProtectionControlOutput(process.exitValue(), bytes.toString(Charsets.UTF_8))
    } catch (failure: Throwable) {
        val termination = runCatching {
            if (process.isAlive) process.destroyForcibly()
            val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            !process.isAlive || process.waitFor(remaining, TimeUnit.NANOSECONDS)
        }
        val ended = termination.getOrDefault(!process.isAlive)
        output.cancel(true)
        if (failure is InterruptedException || termination.exceptionOrNull() is InterruptedException) Thread.currentThread().interrupt()
        if (!ended) {
            throw IllegalStateException(
                "Завершение помощника защиты не подтверждено. Результат команды неизвестен; восстановление сети не выполнялось.",
                failure
            )
        }
        throw IllegalStateException(
            "Ответ службы защиты не подтверждён вовремя или имеет неверный размер. " +
                "Команда могла выполниться; восстановление сети не выполнялось. Перепроверьте защиту.",
            failure
        )
    }
}
