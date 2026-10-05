package app.lernet.desktop.protection

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/** In-memory Process doubles only: no helper, core, Windows API or service starts. */
class ProtectionControlProcessTest {
    @Test fun completedBoundedReplyIsReturned() {
        val process = FakeProcess(ByteArrayInputStream("{\"ok\":true}\n".toByteArray()), alive = false)
        val result = runProtectionControl(process, protectionControlDeadline())
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.text).isEqualTo("{\"ok\":true}\n")
        assertThat(process.kills).isEqualTo(0)
    }

    @Test fun timeoutKillsAndConfirmsOnlyTheHelperWithinTheSharedDeadline() {
        val process = FakeProcess(ByteArrayInputStream(ByteArray(0)), alive = true)
        val started = System.nanoTime()
        val failure = runCatching { runProtectionControl(process, started + 1_060_000_000L) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).contains("Команда могла выполниться")
        assertThat(process.kills).isEqualTo(1)
        assertThat(process.isAlive).isFalse()
        assertThat(System.nanoTime() - started).isLessThan(1_000_000_000L)
    }

    @Test fun excessOutputStopsReadingBeforeLargeAllocationAndKillsTheHelper() {
        val readCount = AtomicInteger()
        val source = object : InputStream() {
            override fun read(): Int { readCount.incrementAndGet(); return 'x'.code }
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                target.fill('x'.code.toByte(), offset, offset + length)
                readCount.addAndGet(length)
                return length
            }
        }
        val process = FakeProcess(source, alive = true)
        val failure = runCatching { runProtectionControl(process, protectionControlDeadline()) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(readCount.get()).isEqualTo(PROTECTION_CONTROL_OUTPUT_LIMIT + 1)
        assertThat(process.kills).isEqualTo(1)
        assertThat(process.isAlive).isFalse()
    }

    @Test fun exitingHelperCannotLeaveCallerWaitingForAnUnclosedOutputPipe() {
        val release = CountDownLatch(1)
        val source = object : InputStream() { override fun read(): Int { release.await(); return -1 } }
        val process = FakeProcess(source, alive = false)
        val started = System.nanoTime()
        try {
            val failure = runCatching { runProtectionControl(process, started + 1_060_000_000L) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(System.nanoTime() - started).isLessThan(1_000_000_000L)
            assertThat(process.kills).isEqualTo(0)
        } finally {
            release.countDown()
        }
    }

    @Test fun unconfirmedTerminationReportsUnknownCommandResult() {
        val process = FakeProcess(ByteArrayInputStream(ByteArray(0)), alive = true, refusesKill = true)
        val started = System.nanoTime()
        val failure = runCatching { runProtectionControl(process, started + 1_060_000_000L) }.exceptionOrNull()
        assertThat(failure?.message).contains("Завершение помощника защиты не подтверждено")
        assertThat(process.kills).isEqualTo(1)
        assertThat(process.isAlive).isTrue()
        assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L)
    }

    @Test fun secondRequestDoesNotGetANewBudgetAfterTheOperationDeadline() {
        val first = FakeProcess(ByteArrayInputStream("{}".toByteArray()), alive = false)
        val deadline = System.nanoTime() + 1_200_000_000L
        runProtectionControl(first, deadline)
        Thread.sleep(220)
        val second = FakeProcess(ByteArrayInputStream(ByteArray(0)), alive = true)
        val started = System.nanoTime()
        assertThat(runCatching { runProtectionControl(second, deadline) }.isFailure).isTrue()
        assertThat(second.kills).isEqualTo(1)
        assertThat(System.nanoTime() - started).isLessThan(500_000_000L)
    }

    private class FakeProcess(
        private val source: InputStream,
        @Volatile private var alive: Boolean,
        private val refusesKill: Boolean = false,
    ) : Process() {
        var kills = 0
        override fun getInputStream(): InputStream = source
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun isAlive(): Boolean = alive
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun waitFor(): Int { check(!alive); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (alive) TimeUnit.NANOSECONDS.sleep(unit.toNanos(timeout).coerceAtLeast(0L))
            return !alive
        }
        override fun destroy() { destroyForcibly() }
        override fun destroyForcibly(): Process {
            kills++
            if (!refusesKill) alive = false
            return this
        }
    }
}
