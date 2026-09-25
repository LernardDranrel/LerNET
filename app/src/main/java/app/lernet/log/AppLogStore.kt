package app.lernet.log

import android.content.Context
import android.os.Looper
import app.lernet.config.redact.SecretRedactor
import app.lernet.engine.log.CrashFailureSink
import app.lernet.engine.log.CrashReport
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.log.CrashTrailSink
import app.lernet.engine.log.DurableFile
import app.lernet.engine.log.DurableRingFile
import app.lernet.engine.log.EngineLogWindow
import app.lernet.engine.log.JournalCeiling
import app.lernet.engine.log.LastCrumbPolicy
import app.lernet.engine.log.SessionHealth
import app.lernet.engine.redact.LerNetLog
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppLogStore(
    private val dir: File,
    private val filesDir: File = dir.parentFile ?: dir,
    private val publicDir: File? = null,
    private val appContext: Context? = null,
) {
    private val sessionFile: File get() = File(dir, SESSION_NAME)
    private val crashFile: File get() = File(dir, CRASH_NAME)
    private val pendingFile: File get() = File(dir, PENDING_NAME)
    private val crumbFile: File get() = File(dir, CRUMB_NAME)
    private val ringFile: File get() = File(dir, RING_NAME)
    private val stateFile: File get() = File(dir, STATE_NAME)
    private val seenFile: File get() = File(dir, SEEN_NAME)
    val crashLastFile: File get() = File(filesDir, CRASH_LAST_NAME)
    private var ringCeilingBytes: Int = JournalCeiling.bytes(JournalCeiling.DEFAULT_MB)
    private var ring = DurableRingFile(ringFile, ringCeilingBytes)
    private val engineWindow = EngineLogWindow()
    private val persistLines = AtomicInteger()
    private val journalWriter = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2_048),
        { runnable -> Thread(runnable, "lernet-journal").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val downloadMirror = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lernet-crumb-mirror").apply { isDaemon = true }
    }
    private val mirrorLock = Any()
    private var mirrorDirty = false
    private var mirrorQueued = false

    @Volatile
    var previousUnclean: Boolean = false
        private set

    @Volatile
    var offerBanner: Boolean = false
        private set

    @Volatile
    private var freezeLastCrumb: Boolean = false

    fun install() {
        dir.mkdirs()
        filesDir.mkdirs()
        publicDir?.mkdirs()
        val previous = readMarker()
        previousUnclean = SessionHealth.previousUnclean(previous)
        offerBanner = SessionHealth.shouldOffer(
            previousMarker = previous,
            pendingExists = pendingFile.exists(),
            ringBytes = ringFile.takeIf { it.exists() }?.length() ?: 0L,
            crashLastModifiedMs = crashLastFile.takeIf { it.exists() && it.length() > 0 }?.lastModified(),
            seenCrashModifiedMs = runCatching { seenFile.takeIf { it.exists() }?.readText()?.toLong() }.getOrNull(),
            acknowledged = ackFile.exists(),
        )
        if (offerBanner) {
            DurableFile.write(pendingFile, "1")
            downloadMirror.execute { mirrorExistingSurvivors() }
        }
        freezeLastCrumb = offerBanner
        DurableFile.write(stateFile, SessionHealth.MARKER_RUNNING)
        LerNetLog.persist = LogPersistToFile()
        CrashTrail.persistCrumb = CrashTrailSink { step ->
            journalWriter.execute {
                ring.append("${Instant.now()} $step")
                mirrorRingAsync()
            }
            val overwrite = LastCrumbPolicy.overwriteLastFile(freezeLastCrumb, step)
            freezeLastCrumb = LastCrumbPolicy.nextFrozen(freezeLastCrumb, step)
            if (overwrite) {
                DurableFile.write(crumbFile, step)
                downloadMirror.execute { PublicCrashMirror.writeFile(publicDir, CRUMB_NAME, step) }
                mirrorDownloads(PublicCrashMirror.DOWNLOAD_CRUMB, step)
            }
        }
        CrashTrail.persistFailure = CrashFailureSink { site, error ->
            journalWriter.execute { writeCaughtFailure(site, error) }
        }
        downloadMirror.execute { flushSession() }
        CrashTrail.mark(
            "log store ready unclean=$previousUnclean offer=$offerBanner " +
                "crashLast=${crashLastFile.exists()} ring=${ringFile.length()}",
        )
    }

    fun shouldOfferAtBoot(): Boolean = offerBanner

    @Synchronized
    fun flushSession(): File {
        dir.mkdirs()
        DurableFile.write(sessionFile, LerNetLog.buffer.snapshot())
        return sessionFile
    }

    fun flushShareBundle(): File {
        val crumb = readOrEmpty(crumbFile)
        val crashLast = readOrEmpty(crashLastFile)
        val session = flushSession().readText()
        val bundle = File(dir, SHARE_NAME)
        FileOutputStream(bundle).use { output ->
            fun write(text: String) = output.write(text.toByteArray(Charsets.UTF_8))
            write("=== CRASH-LAST ===\n${crashLast.ifBlank { "no crash-last" }}\n\n")
            write("=== LAST CRUMB ===\n${crumb.ifBlank { "no crumb" }}\n\n")
            write("=== SESSION-RING ===\n")
            if (ringBytes() == 0L) write("no session-ring\n") else ring.copyTo(output)
            write("\n\n=== ENGINE-TAIL 5MIN ===\n${engineWindow.snapshot().ifBlank { "no engine tail" }}\n\n")
            write("=== SESSION ===\n$session")
            output.fd.sync()
        }
        return bundle
    }

    fun writeCrash(thread: Thread, error: Throwable, pending: Boolean = true) {
        dir.mkdirs()
        val stack = SecretRedactor.redact(error.stackTraceToString())
        val crumb = readOrEmpty(crumbFile)
        val last = CrashReport.renderLast(
            timestamp = Instant.now().toString(),
            threadName = thread.name,
            stack = stack,
            crumb = crumb,
        )
        DurableFile.write(crashLastFile, last)
        DurableFile.write(File(dir, CRASH_LAST_NAME), last)
        ring.append("${Instant.now()} CRASH thread=${thread.name} ${error.javaClass.name}: ${error.message}")
        val ringText = ring.readText()
        PublicCrashMirror.writeFile(publicDir, CRASH_LAST_NAME, last)
        PublicCrashMirror.writeFile(publicDir, RING_NAME, ringText)
        mirrorDownloads(PublicCrashMirror.DOWNLOAD_CRASH_LAST, last)
        mirrorDownloads(PublicCrashMirror.DOWNLOAD_RING, ringText)
        val report = buildString {
            append(last)
            appendLine()
            append(
                CrashReport.render(
                    threadName = thread.name,
                    stack = stack,
                    logs = LerNetLog.buffer.snapshot(),
                ),
            )
        }
        DurableFile.write(crashFile, report)
        PublicCrashMirror.writeFile(publicDir, CRASH_NAME, report)
        PublicCrashMirror.writeFile(publicDir, CRUMB_NAME, crumb.ifBlank { "no crumb" })
        mirrorDownloads(PublicCrashMirror.DOWNLOAD_CRASH, report)
        mirrorDownloads(PublicCrashMirror.DOWNLOAD_CRUMB, crumb.ifBlank { "no crumb" })
        ackFile.delete()
        if (pending) {
            DurableFile.write(pendingFile, "1")
        }
        flushSession()
    }

    fun writeCaughtFailure(site: String, error: Throwable) {
        dir.mkdirs()
        val stack = SecretRedactor.redact(error.stackTraceToString())
        ring.append(
            "${Instant.now()} CAUGHT $site thread=${Thread.currentThread().name} " +
                "${error.javaClass.name}: ${error.message}",
        )
        val ringText = ring.readText()
        PublicCrashMirror.writeFile(publicDir, RING_NAME, ringText)
        mirrorDownloads(PublicCrashMirror.DOWNLOAD_RING, ringText)
        val report = CrashReport.render(
            threadName = Thread.currentThread().name,
            stack = stack,
            logs = LerNetLog.buffer.snapshot(),
        )
        DurableFile.write(crashFile, report)
        PublicCrashMirror.writeFile(publicDir, CRASH_NAME, report)
        flushSession()
    }

    fun pendingCrashFile(): File? {
        if (!offerBanner && !pendingFile.exists() && !previousUnclean) return null
        return crashLastFile.takeIf { it.exists() && it.length() > 0 }
            ?: crashFile.takeIf { it.exists() }
            ?: flushShareBundle()
    }

    fun ringBytes(): Long = if (ringFile.exists()) ringFile.length() else 0L

    fun ringCeiling(): Int = ringCeilingBytes

    fun applyJournalCap(maxBytes: Int) {
        ringCeilingBytes = maxBytes
        ring.applyCap(maxBytes)
    }

    fun clearPending() {
        offerBanner = false
        pendingFile.delete()
        val modified = crashLastFile.takeIf { it.exists() }?.lastModified()
        if (modified != null) {
            DurableFile.write(seenFile, modified.toString())
        }
        dir.mkdirs()
        DurableFile.write(ackFile, "1")
    }

    /** Next process death after Connect is a new episode, even if Close already acked the previous one. */
    fun armCrashWatch() {
        ackFile.delete()
    }

    fun markClean() {
        dir.mkdirs()
        DurableFile.write(stateFile, SessionHealth.MARKER_CLEAN)
        ackFile.delete()
        freezeLastCrumb = false
        CrashTrail.mark(LastCrumbPolicy.CLEAN_MARKER)
    }

    private val ackFile: File get() = File(dir, ACK_NAME)

    private fun readMarker(): String? =
        runCatching { stateFile.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    private fun mirrorExistingSurvivors() {
        val crashLast = readOrEmpty(crashLastFile)
        if (crashLast.isNotBlank()) {
            PublicCrashMirror.writeFile(publicDir, CRASH_LAST_NAME, crashLast)
            mirrorDownloads(PublicCrashMirror.DOWNLOAD_CRASH_LAST, crashLast)
        }
        val ringText = ring.readText()
        if (ringText.isNotBlank()) {
            PublicCrashMirror.writeFile(publicDir, RING_NAME, ringText)
            mirrorDownloads(PublicCrashMirror.DOWNLOAD_RING, ringText)
        }
        val crumb = readOrEmpty(crumbFile)
        if (crumb.isNotBlank()) {
            PublicCrashMirror.writeFile(publicDir, CRUMB_NAME, crumb)
            mirrorDownloads(PublicCrashMirror.DOWNLOAD_CRUMB, crumb)
        }
    }

    private fun mirrorDownloads(name: String, text: String) {
        val ctx = appContext ?: return
        downloadMirror.execute {
            PublicCrashMirror.upsertDownload(ctx, name, text)
        }
    }

    private fun mirrorRingAsync() {
        if (publicDir == null && appContext == null) return
        val enqueue = synchronized(mirrorLock) {
            mirrorDirty = true
            if (mirrorQueued) {
                false
            } else {
                mirrorQueued = true
                true
            }
        }
        if (!enqueue) return
        downloadMirror.execute {
            var more: Boolean
            do {
                synchronized(mirrorLock) { mirrorDirty = false }
                runCatching {
                    val text = ring.readText()
                    PublicCrashMirror.writeFile(publicDir, RING_NAME, text)
                    appContext?.let { PublicCrashMirror.upsertDownload(it, PublicCrashMirror.DOWNLOAD_RING, text) }
                }.onFailure { error -> LerNetLog.w("LerNet.CrashMirror", "ring mirror failed: ${error.message}") }
                more = synchronized(mirrorLock) {
                    if (mirrorDirty) {
                        true
                    } else {
                        mirrorQueued = false
                        false
                    }
                }
            } while (more)
        }
    }

    private fun readOrEmpty(file: File): String =
        runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    private inner class LogPersistToFile : app.lernet.engine.redact.LogPersist {
        override fun onLine(line: String) {
            engineWindow.add(line)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                journalWriter.execute { persistLine(line) }
            } else {
                persistLine(line)
            }
        }
    }

    private fun persistLine(line: String) {
        val urgent = line.contains("dns query fail") ||
            line.contains("HARD_STOP") ||
            line.contains("dns health")
        val lineNumber = persistLines.incrementAndGet()
        ring.append("${Instant.now()} $line", sync = urgent || lineNumber % 16 == 0)
    }

    companion object {
        const val SESSION_NAME = "session.log"
        const val CRASH_NAME = "crash-report.txt"
        const val CRASH_LAST_NAME = "crash-last.txt"
        const val PENDING_NAME = "crash-pending"
        const val CRUMB_NAME = "last-crumb.txt"
        const val RING_NAME = "session-ring.log"
        const val STATE_NAME = "session-state"
        const val SEEN_NAME = "crash-last-seen"
        const val ACK_NAME = "crash-ack"
        const val SHARE_NAME = "lernet-share.txt"
    }
}
