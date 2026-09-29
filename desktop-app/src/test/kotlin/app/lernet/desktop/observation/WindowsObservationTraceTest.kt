package app.lernet.desktop.observation

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WindowsObservationTraceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `constructing and closing idle recorder never touches Windows`() {
        val fake = TraceRunner()
        WindowsObservationTrace(temp.root, fake, scheduler = ManualScheduler()).close()
        assertThat(fake.commands).isEmpty()
    }

    @Test fun `unique bounded metadata recording stops only its own session on deadline`() {
        val fake = TraceRunner()
        val timer = ManualScheduler()
        val trace = recorder(fake, timer)
        assertThat(trace.start().isSuccess).isTrue()
        assertThat(trace.state.value.phase).isEqualTo(ObservationTracePhase.RECORDING)
        val create = fake.commands.single { it.contains("create") }
        assertThat(create).containsAtLeast("-f", "bincirc", "-max", "32", "-rf", "00:00:45", "Microsoft-Windows-TCPIP")
        assertThat(create).doesNotContain("Microsoft-Windows-NDIS-PacketCapture")
        assertThat(timer.delay).isEqualTo(45_000)
        timer.fire()
        assertThat(trace.state.value.phase).isEqualTo(ObservationTracePhase.FINISHED)
        val stop = fake.commands.single { it.contains("stop") }
        assertThat(stop).containsExactly(fake.executable, "stop", "LerNET-observe-test-id", "-ets").inOrder()
        trace.close()
    }

    @Test fun `recording whose file identity changed is not stopped`() {
        val fake = TraceRunner()
        val trace = recorder(fake, ManualScheduler())
        trace.start()
        fake.queryFile = File(temp.root, "foreign.etl").absolutePath
        assertThat(trace.stop().isFailure).isTrue()
        assertThat(trace.state.value.phase).isEqualTo(ObservationTracePhase.ERROR)
        assertThat(fake.commands.none { it.contains("stop") }).isTrue()
        trace.close()
    }

    @Test fun `logman sequence suffix is the actual retained local file`() {
        val fake = TraceRunner()
        val trace = recorder(fake, ManualScheduler())
        val base = trace.start().getOrThrow()
        fake.queryFile = base.absolutePath.removeSuffix(".etl") + "_000001.etl"
        assertThat(trace.stop().getOrThrow()?.name).isEqualTo("LerNET-observe-test-id_000001.etl")
        trace.close()
    }

    @Test fun `close stops owned recording and cannot restart it`() {
        val fake = TraceRunner()
        val trace = recorder(fake, ManualScheduler())
        trace.start()
        trace.close()
        assertThat(fake.commands.count { it.contains("stop") }).isEqualTo(1)
        assertThat(trace.start().isFailure).isTrue()
    }

    @Test fun `failed create does not stop an unrelated session`() {
        val fake = TraceRunner().apply { createExitCode = 5 }
        val trace = recorder(fake, ManualScheduler())
        assertThat(trace.start().isFailure).isTrue()
        assertThat(fake.commands.none { it.contains("stop") }).isTrue()
        trace.close()
    }

    @Test fun `timed out create reconciles and cleans up only proven owned recording`() {
        val fake = TraceRunner().apply { createTimeout = true }
        val trace = recorder(fake, ManualScheduler())
        assertThat(trace.start().isFailure).isTrue()
        assertThat(fake.commands.count { it.contains("stop") }).isEqualTo(1)
        trace.close()
    }

    @Test fun `cancellation while command starts session still reconciles owned cleanup`() {
        val fake = TraceRunner().apply { createInterrupted = true }
        val trace = recorder(fake, ManualScheduler())
        assertThat(trace.start().isFailure).isTrue()
        assertThat(fake.commands.count { it.contains("stop") }).isEqualTo(1)
        trace.close()
    }

    @Test fun `occupied unique session name is left untouched`() {
        val fake = TraceRunner().apply { existing = true }
        val trace = recorder(fake, ManualScheduler())
        assertThat(trace.start().isFailure).isTrue()
        assertThat(fake.commands.none { it.contains("create") || it.contains("stop") }).isTrue()
        trace.close()
    }

    @Test fun `unavailable ownership inspection prevents creating a recording`() {
        val fake = TraceRunner().apply { identityUnavailable = true }
        val trace = recorder(fake, ManualScheduler())
        assertThat(trace.start().isFailure).isTrue()
        assertThat(fake.commands.none { it.contains("create") || it.contains("stop") }).isTrue()
        trace.close()
    }

    @Test fun `transient ownership inspection failure retries only owned session`() {
        val fake = TraceRunner()
        val timer = ManualScheduler()
        val trace = recorder(fake, timer)
        trace.start()
        fake.identityUnavailable = true
        assertThat(trace.stop().isFailure).isTrue()
        assertThat(timer.delay).isEqualTo(2_000)
        assertThat(fake.commands.none { it.contains("stop") }).isTrue()
        fake.identityUnavailable = false
        timer.fire()
        assertThat(trace.state.value.phase).isEqualTo(ObservationTracePhase.FINISHED)
        assertThat(fake.commands.count { it.contains("stop") }).isEqualTo(1)
        trace.close()
    }

    @Test fun `closed window releases scheduler after exhausted ownership retries`() {
        val fake = TraceRunner()
        val timer = ManualScheduler()
        val trace = recorder(fake, timer)
        trace.start().getOrThrow()
        fake.identityUnavailable = true
        trace.close()
        repeat(3) { timer.fire() }
        assertThat(timer.closed).isTrue()
        assertThat(timer.pending).isNull()
        assertThat(trace.state.value.phase).isEqualTo(ObservationTracePhase.ERROR)
        assertThat(fake.commands.none { it.contains("stop") }).isTrue()
    }

    private fun recorder(runner: TraceRunner, scheduler: ManualScheduler) = WindowsObservationTrace(
        temp.root, runner, scheduler = scheduler, clock = { 100 }, sessionId = { "test-id" })

    private class ManualScheduler : ObservationTraceScheduler, Closeable {
        var closed = false
        override fun close() { closed = true; pending = null }
        var delay: Long = 0
        var pending: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, action: () -> Unit): Closeable {
            delay = delayMillis
            pending = action
            return Closeable { if (pending === action) pending = null }
        }
        fun fire() { pending?.also { pending = null }?.invoke() }
    }

    private class TraceRunner : ObservationCommandRunner {
        val commands = mutableListOf<List<String>>()
        var executable = ""
        var queryFile: String? = null
        var existing = false
        var created = false
        var createExitCode = 0
        var createTimeout = false
        var createInterrupted = false
        var identityUnavailable = false
        override fun run(arguments: List<String>, timeoutMs: Long): ObservationCommandResult {
            assertThat(timeoutMs).isEqualTo(5_000)
            commands += arguments
            if (arguments.first().endsWith("logman.exe")) executable = arguments.first()
            return when {
                arguments.contains("-EncodedCommand") -> ObservationCommandResult(0, buildJsonObject {
                    put("state", if (identityUnavailable) "ERROR" else if (existing || created) "AVAILABLE" else "ABSENT")
                    put("path", queryFile ?: "C:\\other.etl")
                }.toString())
                arguments.contains("providers") -> ObservationCommandResult(0, "Provider registered")
                arguments.contains("query") -> if (existing || created)
                    ObservationCommandResult(0, "Output file: ${queryFile ?: "C:\\other.etl"}\n") else ObservationCommandResult(1, "Absent")
                arguments.contains("create") -> {
                    created = createExitCode == 0 || createTimeout
                    queryFile = arguments[arguments.indexOf("-o") + 1]
                    if (createInterrupted) throw InterruptedException("Fake cancellation after start")
                    ObservationCommandResult(if (createTimeout) null else createExitCode, "", createTimeout)
                }
                arguments.contains("stop") -> { created = false; ObservationCommandResult(0, "Stopped") }
                else -> error("Unexpected fake command")
            }
        }
    }
}
