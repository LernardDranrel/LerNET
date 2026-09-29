package app.lernet.desktop.observation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class ObservationTracePhase { IDLE, STARTING, RECORDING, STOPPING, FINISHED, ERROR }

internal data class ObservationTraceState(
    val phase: ObservationTracePhase = ObservationTracePhase.IDLE,
    val title: String = "Короткая запись событий",
    val detail: String = "45 секунд · TCP/IP · до 32 МБ · файл остаётся на устройстве",
    val sessionName: String = "",
    val file: File? = null,
    val startedAt: Long? = null,
    val endsAt: Long? = null,
)

internal fun interface ObservationTraceScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): Closeable
}

/** One UUID-named ETW session. Does not touch global pktmon, netsh trace, or WFP settings. */
internal class WindowsObservationTrace(
    private val outputDirectory: File,
    private val runner: ObservationCommandRunner = SystemObservationCommandRunner,
    private val durationMillis: Long = 45_000,
    private val scheduler: ObservationTraceScheduler = DaemonTraceScheduler(),
    private val systemDirectory: File = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32"),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionId: () -> String = { UUID.randomUUID().toString() },
) : Closeable {
    private val mutableState = MutableStateFlow(ObservationTraceState())
    val state: StateFlow<ObservationTraceState> = mutableState.asStateFlow()
    private var owned: OwnedSession? = null
    private var attempted: OwnedSession? = null
    private var deadline: Closeable? = null
    private var closed = false
    private var stopRetries = 0

    init { require(durationMillis in 30_000..60_000) { "Trace duration must be 30–60 seconds" } }

    @Synchronized
    fun start(): Result<File> {
        if (closed) return Result.failure(IllegalStateException("Окно записи закрыто."))
        if (owned != null || attempted != null) return Result.failure(IllegalStateException("Запись уже запущена; сначала завершите её."))
        stopRetries = 0
        val suffix = sessionId()
        require(suffix.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        val name = "LerNET-observe-$suffix"
        val file = File(outputDirectory, "$name.etl").absoluteFile
        mutableState.value = ObservationTraceState(ObservationTracePhase.STARTING,
            "Готовим локальную запись", "Проверяем отдельную сессию LerNET; чужие записи продолжат работать.", name, file)
        return try {
            check(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "Не удалось создать папку для записи." }
            // Probe this exact name, never enumerate or stop someone else's recording.
            val existing = identity(name)
            check(existing.status == "ABSENT") { "Windows не подтвердила свободное имя и доступ к сведениям сессии. Чужая запись не изменена." }
            val provider = command("query", "providers", "Microsoft-Windows-TCPIP")
            check(!provider.timedOut && provider.exitCode == 0) { "Источник TCP/IP недоступен на этой Windows." }
            val seconds = durationMillis / 1_000
            val runFor = "00:${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
            // Remember the attempt before spawning logman: cancellation can interrupt its waiter
            // after Windows has already created the ETW session. Cleanup still requires CIM proof.
            attempted = OwnedSession(name, file)
            val created = command("create", "trace", name, "-p", "Microsoft-Windows-TCPIP", "0xffffffffffffffff", "5",
                "-o", file.absolutePath, "-f", "bincirc", "-max", "32", "-rf", runFor, "-ets")
            if (created.timedOut || created.exitCode != 0) {
                // A timed-out controller command could have started the session. Reconcile only if
                // Windows proves that this UUID session writes to our exact file.
                val actual = ownedFile(identity(name), file)
                if (actual != null) {
                    owned = OwnedSession(name, actual)
                    stop()
                }
                error(if (created.timedOut) "Windows не подтвердила запуск записи вовремя." else
                    "Windows отказала в запуске записи (код ${created.exitCode}). Проверьте права администратора.")
            }
            owned = OwnedSession(name, file)
            attempted = null
            val actual = ownedFile(identity(name), file)
                ?: error("Windows не подтвердила путь нашего файла записи.")
            owned = OwnedSession(name, actual)
            stopRetries = 0
            val now = clock()
            mutableState.value = ObservationTraceState(ObservationTracePhase.RECORDING,
                "Записываем события TCP/IP", "Теперь повторите сбой. Запись завершится сама; полный захват пакетов не включён.",
                name, actual, now, now + durationMillis)
            deadline = scheduler.schedule(durationMillis) { stop() }
            Result.success(actual)
        } catch (error: Exception) {
            if ((owned != null || attempted != null) && mutableState.value.phase != ObservationTracePhase.ERROR) stop()
            mutableState.value = mutableState.value.copy(phase = ObservationTracePhase.ERROR,
                title = if (owned == null && attempted == null) "Запись не началась" else "Windows не подтвердила остановку",
                detail = if (owned == null && attempted == null) error.message ?: "Windows не предоставила запись." else
                    "${error.message} ${mutableState.value.detail}")
            Result.failure(error)
        }
    }

    @Synchronized
    fun stop(): Result<File?> {
        val session = owned ?: attempted ?: return Result.success(mutableState.value.file)
        mutableState.value = mutableState.value.copy(phase = ObservationTracePhase.STOPPING,
            title = "Завершаем запись", detail = "Останавливаем только сессию, созданную этим окном.")
        return try {
            stopOwned()
            deadline?.close()
            deadline = null
            mutableState.value = mutableState.value.copy(phase = ObservationTracePhase.FINISHED,
                title = "Запись завершена", detail = if (mutableState.value.file?.isFile == true)
                    "Локальный ETL готов. Он содержит системные события и адреса соединений; никакой файл не отправлен."
                else "Сессия остановлена, но ожидаемый ETL пока не найден. LerNET не подменяет отсутствие файла успешным результатом.")
            if (closed) (scheduler as? Closeable)?.close()
            Result.success(mutableState.value.file ?: session.file)
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(phase = ObservationTracePhase.ERROR,
                title = "Windows не подтвердила остановку", detail =
                    "${error.message} Сессия: ${session.name}. Чужие записи не затронуты.")
            if (stopRetries++ < 3) {
                deadline?.close()
                deadline = scheduler.schedule(2_000) { stop() }
            } else if (closed) {
                // Closing after failed ownership checks must not retain this window's scheduler.
                // Ownership remains unproven: never compensate by stopping a different session.
                deadline?.close()
                deadline = null
                (scheduler as? Closeable)?.close()
            }
            Result.failure(error)
        }
    }

    private fun stopOwned() {
        val session = owned ?: attempted ?: return
        val identity = identity(session.name)
        if (identity.status == "ABSENT") { owned = null; attempted = null; return }
        val actual = ownedFile(identity, session.file)
        check(actual != null) { "Принадлежность сессии LerNET не подтверждена; остановка отменена." }
        mutableState.value = mutableState.value.copy(file = actual)
        val stopped = command("stop", session.name, "-ets")
        check(!stopped.timedOut && stopped.exitCode == 0) { "Команда остановки завершилась с кодом ${stopped.exitCode}." }
        owned = null
        attempted = null
    }

    private fun ownedFile(result: SessionIdentity, file: File): File? {
        if (result.status != "AVAILABLE") return null
        // Logman may append a sequence suffix. Never accept another directory or another UUID.
        val originalStem = file.absolutePath.removeSuffix(".etl").replace(Regex("_\\d{6}$"), "")
        val pattern = Regex(Regex.escape(originalStem) + "(?:_\\d{6})?\\.etl", RegexOption.IGNORE_CASE)
        return result.filePath.takeIf { pattern.matches(it) }?.let(::File)
    }

    private fun identity(name: String): SessionIdentity {
        // The documented CIM LocalFilePath is stable across Windows display languages.
        // UUID names above contain only alphanumerics/hyphens; no caller text enters this script.
        val script = """
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new()
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                ${'$'}session = Get-CimInstance -Namespace 'root/Microsoft/Windows/EventTracingManagement' -ClassName 'MSFT_EtwTraceSession' -Filter "Name='$name'"
                if (${ '$' }null -eq ${ '$' }session) { @{state='ABSENT';path=''} | ConvertTo-Json -Compress }
                else { @{state='AVAILABLE';path=[string]${ '$' }session.LocalFilePath} | ConvertTo-Json -Compress }
            } catch { @{state='ERROR';path=''} | ConvertTo-Json -Compress }
        """.trimIndent()
        val result = runner.run(listOf(File(systemDirectory, "WindowsPowerShell/v1.0/powershell.exe").absolutePath,
            "-NoProfile", "-NonInteractive", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))), 5_000)
        if (result.timedOut || result.exitCode != 0) return SessionIdentity("ERROR", "")
        return try {
            val parsed = Json.parseToJsonElement(result.output.trim().removePrefix("\uFEFF")) as JsonObject
            SessionIdentity(parsed["state"]?.jsonPrimitive?.content.orEmpty(), parsed["path"]?.jsonPrimitive?.content.orEmpty())
        } catch (_: Exception) { SessionIdentity("ERROR", "") }
    }

    private fun command(vararg arguments: String) = runner.run(
        listOf(File(systemDirectory, "logman.exe").absolutePath) + arguments, 5_000)

    @Synchronized
    override fun close() {
        closed = true
        stop()
        if (owned == null && attempted == null) {
            deadline?.close()
            deadline = null
            (scheduler as? Closeable)?.close()
        }
    }

    private data class OwnedSession(val name: String, val file: File)
    private data class SessionIdentity(val status: String, val filePath: String)

}

private class DaemonTraceScheduler : ObservationTraceScheduler, Closeable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lernet-observation-trace").apply { isDaemon = true }
    }
    override fun schedule(delayMillis: Long, action: () -> Unit): Closeable {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return Closeable { future.cancel(false) }
    }
    override fun close() { executor.shutdown() }
}
