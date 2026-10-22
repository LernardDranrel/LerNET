package app.lernet.desktop

import app.lernet.config.redact.SecretRedactor
import app.lernet.engine.policy.PolicyControlCapabilities
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class TunnelStatus { STOPPED, STARTING, RUNNING, RECONNECTING, FAILED }
enum class TunnelFailure { TUN_PERMISSION }

data class TunnelSnapshot(
    val status: TunnelStatus = TunnelStatus.STOPPED,
    val message: String = "",
    val logs: List<String> = emptyList(),
    val reconnectAttempt: Int = 0,
    val failure: TunnelFailure? = null,
)

interface TunnelLifecycle {
    fun coreRunning()
    fun tunnelFailed()
}

/** Exact launch inputs stay in memory; default identity-based toString never exposes the configuration. */
internal class WindowsLaunchSnapshot(val executable: Path, val config: String)

/** The Windows executable owns TUN, routes and DNS; the desktop UI never handles packets. */
class WindowsBoxProcess internal constructor(
    private val directory: Path,
    private val launchRun: (Path, Path) -> Process,
    private val checkCommand: ((Path, List<String>) -> String)?,
    private val readinessDelayMs: Long,
    private val retryDelayMs: (Int) -> Long = { (it.coerceAtMost(15) * 2_000L).coerceAtMost(30_000L) },
) : AutoCloseable {
    /** Raw core log lines for passive checks (the block detector). Never blocks the reader. */
    @Volatile var lineListener: ((String) -> Unit)? = null
    constructor(directory: Path) : this(directory, { executable, configFile ->
        ProcessBuilder(executable.toString(), "run", "-c", configFile.toString())
            .directory(directory.toFile()).redirectErrorStream(true).start()
    }, null, 900)

    val policyControlCapabilities = PolicyControlCapabilities.RESTART_ONLY
    private val generation = AtomicLong()
    private val mutable = MutableStateFlow(TunnelSnapshot())
    val state: StateFlow<TunnelSnapshot> = mutable

    @Volatile private var process: Process? = null

    @Volatile private var desired = false
    private val processLock = Any()
    private var runningLaunch: WindowsLaunchSnapshot? = null

    @Volatile var journalMaxMb: Int = 100

    /** Told when a core (re)start is up and when the tunnel gives up for good. */
    @Volatile var lifecycle: TunnelLifecycle? = null
    private val journalLock = Any()

    fun start(executable: Path, config: String) {
        start(WindowsLaunchSnapshot(executable, config))
    }

    internal fun start(launch: WindowsLaunchSnapshot) = synchronized(processLock) {
        check(stop(waitForExit = true)) { "Предыдущее ядро ещё не завершилось. Новый туннель не запущен." }
        val ticket = generation.incrementAndGet()
        desired = true
        mutable.value = TunnelSnapshot(TunnelStatus.STARTING, "Проверяем конфигурацию")
        thread(name = "lernet-windows-engine", isDaemon = true) {
            var configFile: Path? = null
            var lastProcess: Process? = null
            try {
                val version = commandOutput(launch.executable, "version")
                check(version.contains(PINNED_CORE_VERSION)) {
                    "Нужно ядро $PINNED_CORE_VERSION; найдено: ${version.lineSequence().firstOrNull().orEmpty()}"
                }
                Files.createDirectories(directory)
                // Independent files prevent a cancelled preflight from overwriting a newer launch's configuration.
                val activeFile = Files.createTempFile(directory, "active-$ticket-", ".json")
                configFile = activeFile
                Files.writeString(activeFile, launch.config)
                val checked = commandOutput(launch.executable, "check", "-c", activeFile.toString())
                check(!checked.contains("FATAL", ignoreCase = true)) { checked }
                if (generation.get() != ticket) return@thread
                var attempt = 0
                while (desired && generation.get() == ticket) {
                    // Stop invalidates the ticket under the same lock used for actual process creation.
                    val running = synchronized(processLock) {
                        if (!desired || generation.get() != ticket) return@thread
                        mutable.update {
                            it.copy(
                                status = if (attempt == 0) TunnelStatus.STARTING else TunnelStatus.RECONNECTING,
                                message = if (attempt == 0) "Запускаем туннель" else "VPN включён · восстановление, попытка $attempt",
                                reconnectAttempt = attempt
                            )
                        }
                        launchRun(launch.executable, activeFile).also {
                            process = it
                            lastProcess = it
                            runningLaunch = launch
                        }
                    }
                    val lastFatal = AtomicReference<String?>(null)
                    if (!desired || generation.get() != ticket) {
                        running.destroy()
                        clearProcess(running)
                        return@thread
                    }
                    val logReader = thread(name = "lernet-windows-log", isDaemon = true) {
                        running.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (line.contains("FATAL", ignoreCase = true)) lastFatal.set(line)
                                runCatching { lineListener?.invoke(line) }
                                val safeLine = SecretRedactor.redact(line)
                                mutable.update { it.copy(logs = (it.logs + safeLine).takeLast(400)) }
                                runCatching { appendJournal(safeLine) }
                            }
                        }
                    }
                    if (readinessDelayMs > 0) Thread.sleep(readinessDelayMs)
                    synchronized(processLock) {
                        if (running.isAlive && desired && generation.get() == ticket && process === running) {
                            mutable.update {
                                it.copy(status = TunnelStatus.RUNNING, message = "Подключено", reconnectAttempt = attempt)
                            }
                            lifecycle?.let { listener -> runCatching { listener.coreRunning() } }
                        }
                    }
                    val code = running.waitFor()
                    logReader.join(1_000)
                    synchronized(processLock) {
                        clearProcess(running)
                        if (!desired || generation.get() != ticket) return@thread
                        if (isTunPermissionFailure(lastFatal.get())) {
                            mutable.update {
                                it.copy(
                                    status = TunnelStatus.FAILED,
                                    message = "Windows не дала включить VPN. Запустите LerNET от имени администратора " +
                                        "или выберите режим «Прокси» в настройках.",
                                    failure = TunnelFailure.TUN_PERMISSION
                                )
                            }
                            desired = false
                            lifecycle?.let { listener -> runCatching { listener.tunnelFailed() } }
                            return@thread
                        }
                        attempt++
                        // Plain words as on the phone; the raw core line and exit code stay in the technical journal.
                        val reason = lastFatal.get()?.let { " · " + app.lernet.engine.text.CoreFailureWords.title(it) }.orEmpty()
                        logDiagnostic("core exited (code $code)" + lastFatal.get()?.let { ": " + SecretRedactor.redact(it).take(180) }.orEmpty())
                        mutable.update {
                            it.copy(status = TunnelStatus.RECONNECTING,
                                message = "VPN включён · подключение прервалось, восстанавливаем$reason")
                        }
                    }
                    Thread.sleep(retryDelayMs(attempt))
                }
            } catch (error: Exception) {
                synchronized(processLock) {
                    if (generation.get() == ticket) {
                        desired = false
                        mutable.update {
                            it.copy(
                                status = TunnelStatus.FAILED,
                                message = app.lernet.engine.text.UserText.of(error, "VPN не запустился. Подробности — в журнале.")
                            )
                        }
                        lifecycle?.let { listener -> runCatching { listener.tunnelFailed() } }
                    }
                }
            } finally {
                if (lastProcess?.isAlive != true) configFile?.let(Files::deleteIfExists)
            }
        }
        Unit
    }

    /** False means shutdown is still pending or could not be confirmed; callers must not acquire another TUN. */
    fun stop(waitForExit: Boolean = false): Boolean = synchronized(processLock) {
        desired = false
        val ticket = generation.incrementAndGet()
        val running = process
        if (running != null) {
            running.destroy()
            val finish = {
                if (!running.waitFor(2, TimeUnit.SECONDS)) {
                    running.destroyForcibly()
                    running.waitFor(1, TimeUnit.SECONDS)
                }
                val stopped = !running.isAlive
                synchronized(processLock) {
                    if (stopped && process === running) {
                        process = null
                        runningLaunch = null
                    }
                    if (generation.get() == ticket) {
                        mutable.update {
                            it.copy(
                                status = if (stopped) TunnelStatus.STOPPED else TunnelStatus.FAILED,
                                message = if (stopped) "Отключено" else "Не удалось подтвердить, что VPN остановлен", reconnectAttempt = 0
                            )
                        }
                    }
                }
                stopped
            }
            if (waitForExit) {
                return@synchronized finish()
            } else {
                thread(name = "lernet-windows-stop", isDaemon = true) { finish() }
                mutable.update { it.copy(status = TunnelStatus.STOPPED, message = "Отключаем", reconnectAttempt = 0) }
                return@synchronized false
            }
        }
        runningLaunch = null
        mutable.update { it.copy(status = TunnelStatus.STOPPED, message = "Отключено", reconnectAttempt = 0) }
        true
    }

    internal fun captureRunningLaunch(): WindowsLaunchSnapshot? = synchronized(processLock) {
        runningLaunch.takeIf { desired && mutable.value.status == TunnelStatus.RUNNING && process?.isAlive == true }
    }

    internal fun hasOwnedProcess(): Boolean = synchronized(processLock) { process?.isAlive == true }

    private fun clearProcess(running: Process) = synchronized(processLock) {
        if (process === running && !running.isAlive) {
            process = null
            runningLaunch = null
        }
    }

    override fun close() {
        stop()
    }

    /** Keep failed preflight details alongside the regular core journal and in the diagnostics tab. */
    fun logDiagnostic(message: String) {
        val safeMessage = SecretRedactor.redact(message).replace('\r', ' ').replace('\n', ' ').take(2048)
        val line = "${OffsetDateTime.now()} [preflight] $safeMessage"
        mutable.update { it.copy(logs = (it.logs + line).takeLast(400)) }
        runCatching { appendJournal(line) }
    }

    /**
     * «Сохранить журнал»: the previous and the current segment, oldest first, with secrets removed once more
     * (lines are already redacted on write). [header] goes on top: version and system, no personal data.
     */
    fun journalText(header: String, maxBytes: Long = 20L * 1024 * 1024): String = synchronized(journalLock) {
        val parts = listOf(directory.resolve("session.log.1"), directory.resolve("session.log"))
            .filter(Files::exists)
            .map { path ->
                val size = Files.size(path)
                Files.newInputStream(path).use { input ->
                    if (size > maxBytes) input.skipNBytes(size - maxBytes)
                    String(input.readAllBytes(), Charsets.UTF_8)
                }
            }
        val body = parts.joinToString("").takeLast(maxBytes.toInt())
        header.trimEnd() + "\n\n" + SecretRedactor.redact(body.ifBlank { "Журнал пуст: подключений в этой установке ещё не было.\n" })
    }

    private fun appendJournal(line: String) = synchronized(journalLock) {
        Files.createDirectories(directory)
        val current = directory.resolve("session.log")
        val previous = directory.resolve("session.log.1")
        val limit = journalMaxMb.coerceIn(1, 500).toLong() * 1024 * 1024
        val segmentLimit = limit / 2
        val bytes = (SecretRedactor.redact(line).take(8192) + "\n").toByteArray(Charsets.UTF_8)
        retainTail(current, segmentLimit)
        retainTail(previous, segmentLimit)
        if (Files.exists(current) && Files.size(current) + bytes.size > segmentLimit) {
            Files.move(current, previous, StandardCopyOption.REPLACE_EXISTING)
        }
        Files.write(current, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun retainTail(path: Path, maxBytes: Long) {
        if (!Files.exists(path) || Files.size(path) <= maxBytes) return
        val temp = path.resolveSibling(path.fileName.toString() + ".trim")
        Files.newInputStream(path).use { input ->
            input.skipNBytes(Files.size(path) - maxBytes)
            Files.newOutputStream(temp).use { output -> input.copyTo(output) }
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun commandOutput(executable: Path, vararg args: String): String {
        checkCommand?.let { return it(executable, args.toList()) }
        check(Files.isRegularFile(executable)) { "Не найден sing-box.exe: $executable" }
        Files.createDirectories(directory)
        val outputFile = Files.createTempFile(directory, "core-check-", ".log")
        try {
            val checked = ProcessBuilder(listOf(executable.toString()) + args)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start()
            check(checked.waitFor(15, TimeUnit.SECONDS)) {
                checked.destroyForcibly()
                "sing-box не ответил за 15 секунд"
            }
            val bytes = Files.newInputStream(outputFile).use { it.readNBytes(64_000) }
            val output = bytes.toString(Charsets.UTF_8)
            check(checked.exitValue() == 0) { output.ifBlank { "sing-box завершился с кодом " + checked.exitValue() } }
            return output
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }

    companion object {
        const val PINNED_CORE_VERSION = "1.14.1-lx.8"

        internal fun isTunPermissionFailure(line: String?): Boolean = line != null &&
            line.contains("configure tun interface", ignoreCase = true) &&
            (
                line.contains("Access is denied", ignoreCase = true) ||
                    line.contains("permission denied", ignoreCase = true) ||
                    line.contains("Отказано в доступе", ignoreCase = true)
                )
    }
}
