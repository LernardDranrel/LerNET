package app.lernet.desktop

import app.lernet.engine.RunMode
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** Process doubles never execute the bundled core or touch the host network. */
class WindowsSimpleRestorationTest {
    private val saved = StoredState(
        profiles = listOf(StoredProfile("original", "Original", "test", emptyList(), "out")),
        selectedProfileId = "original",
        mode = RunMode.FULL_VPN.name,
    )
    private val launch = WindowsLaunchSnapshot(Path.of("exact-old-core.exe"), "exact old private configuration")
    private val connection = WindowsSimpleConnection(launch, "original", RunMode.FULL_VPN, saved, 12)

    @Test
    fun failedExpertStartCanRestoreOnlyTheExactRunningSimpleLaunch() {
        val lease = requireNotNull(WindowsSimpleRestoreLease.capture(connection, launch, true, 12, 13, saved))
        assertThat(lease.connection.launch).isSameInstanceAs(launch)
        assertThat(lease.connection.mode).isEqualTo(RunMode.FULL_VPN)
        assertThat(lease.permitsRestore(13, saved, false)).isTrue()
        assertThat(launch.toString()).doesNotContain(launch.config)
    }

    @Test
    fun stoppedStartingReconnectingOrUnconfirmedSimpleCannotBecomeARestoreLease() {
        assertThat(WindowsSimpleRestoreLease.capture(connection, null, true, 12, 13, saved)).isNull()
        assertThat(WindowsSimpleRestoreLease.capture(connection, launch, false, 12, 13, saved)).isNull()
        val other = WindowsLaunchSnapshot(launch.executable, launch.config)
        assertThat(WindowsSimpleRestoreLease.capture(connection, other, true, 12, 13, saved)).isNull()
        assertThat(WindowsSimpleRestoreLease.capture(connection, launch, true, 14, 15, saved)).isNull()
    }

    @Test
    fun laterUserStopConnectOrModeIntentNeverResurrectsThePreviousTunnel() {
        val lease = requireNotNull(WindowsSimpleRestoreLease.capture(connection, launch, true, 12, 13, saved))
        assertThat(lease.permitsRestore(14, saved, false)).isFalse()
        assertThat(lease.permitsRestore(13, saved.copy(mode = RunMode.PROXY.name), false)).isFalse()
        assertThat(lease.permitsRestore(13, saved.copy(selectedProfileId = "another"), false)).isFalse()
        assertThat(lease.permitsRestore(13, saved.copy(profiles = emptyList()), false)).isFalse()
        assertThat(lease.permitsRestore(13, saved, true)).isFalse()
    }

    @Test
    fun changedProfileOrRoutingConfigurationIsNotSilentlyReplacedByTheOldSnapshot() {
        val lease = requireNotNull(WindowsSimpleRestoreLease.capture(connection, launch, true, 12, 13, saved))
        val edited = saved.copy(profiles = listOf(saved.profiles.single().copy(selectedOutboundId = "new-out")))
        assertThat(lease.permitsRestore(13, edited, false)).isFalse()
        assertThat(lease.permitsRestore(13, saved.copy(directDnsServer = "8.8.8.8"), false)).isFalse()
        assertThat(WindowsSimpleRestoreLease.capture(connection, launch, true, 12, 13, edited)).isNull()
    }

    @Test
    fun systemExpertProtectionRejectsSimpleConnectBeforeAnyEngineStarts() {
        val controller = DesktopController(DesktopStore(Files.createTempDirectory("lernet-restriction-test")))
        try {
            controller.simpleModeRestriction = { "Системная защита Expert блокирует обычный VPN" }
            controller.connect()
            assertThat(controller.state.value.connectionError?.message).contains("Системная защита Expert")
            assertThat(controller.tunnel.state.value.status).isEqualTo(TunnelStatus.STOPPED)
            assertThat(controller.tunnel.hasOwnedProcess()).isFalse()
        } finally {
            controller.close()
        }
    }

    @Test
    fun unknownSystemProtectionFailsClosedBeforeSimpleConnect() {
        val controller = DesktopController(DesktopStore(Files.createTempDirectory("lernet-restriction-error-test")))
        try {
            controller.simpleModeRestriction = { error("guard status unavailable") }
            controller.connect()
            assertThat(controller.state.value.connectionError?.message).contains("Не удалось проверить системную защиту")
            assertThat(controller.tunnel.state.value.status).isEqualTo(TunnelStatus.STOPPED)
            assertThat(controller.tunnel.hasOwnedProcess()).isFalse()
        } finally {
            controller.close()
        }
    }

    @Test
    fun diagnosticsAndCoreReaderRedactSecretsBeforeUiJournalAndTruncation() = runBlocking {
        val id = "11111111-2222-3333-4444-555555555555"
        val secret = "long-private-password".repeat(140)
        val raw = """{"uuid":"$id","password":"$secret"} vless://$id@example.invalid?pbk=private-key sid:abcd"""
        val directory = Files.createTempDirectory("lernet-log-redaction-test")
        val fake = FakeProcess(logInput = raw + "\n")
        val tunnel = WindowsBoxProcess(
            directory, { _, _ -> fake },
            { _, args -> if (args.first() == "version") WindowsBoxProcess.PINNED_CORE_VERSION else "" }, 0
        )
        try {
            tunnel.logDiagnostic(raw)
            val diagnostic = tunnel.state.value.logs.single()
            assertThat(diagnostic).doesNotContain(id)
            assertThat(diagnostic).doesNotContain("long-private-password")
            assertThat(diagnostic).doesNotContain("private-key")
            tunnel.start(launch)
            withTimeout(10_000) { tunnel.state.first { it.logs.isNotEmpty() } }
            check(fake.logReadCompleted.await(10, TimeUnit.SECONDS))
            val nativeLine = tunnel.state.value.logs.single()
            assertThat(nativeLine).doesNotContain(id)
            assertThat(nativeLine).doesNotContain("long-private-password")
            assertThat(nativeLine).doesNotContain("private-key")
            assertThat(nativeLine).doesNotContain("sid:abcd")
            val journal = Files.readString(directory.resolve("session.log"))
            assertThat(journal.lineSequence().filter { it.isNotBlank() }.count()).isEqualTo(2)
            assertThat(journal).doesNotContain(id)
            assertThat(journal).doesNotContain("long-private-password")
            assertThat(journal).doesNotContain("private-key")
        } finally {
            fake.exit()
            tunnel.stop(waitForExit = true)
        }
    }

    @Test
    fun runningSnapshotRequiresALiveOwnedProcessAndConfirmedShutdown() = runBlocking {
        val fake = FakeProcess()
        val tunnel = fakeTunnel(fake)
        try {
            tunnel.start(launch)
            withTimeout(10_000) { tunnel.state.first { it.status == TunnelStatus.RUNNING } }
            assertThat(tunnel.captureRunningLaunch()).isSameInstanceAs(launch)
            assertThat(tunnel.stop(waitForExit = true)).isTrue()
            assertThat(tunnel.captureRunningLaunch()).isNull()
            assertThat(tunnel.hasOwnedProcess()).isFalse()
            assertThat(tunnel.state.value.status).isEqualTo(TunnelStatus.STOPPED)
        } finally {
            fake.exit()
            tunnel.stop(waitForExit = true)
        }
    }

    @Test
    fun failedKillKeepsOwnershipAndPreventsAnotherTunFromStarting() = runBlocking {
        val fake = FakeProcess(terminateOnDestroy = false)
        val launches = AtomicInteger()
        val tunnel = WindowsBoxProcess(Files.createTempDirectory("lernet-fake-kill"), { _, _ ->
            launches.incrementAndGet()
            fake
        }, { _, args -> if (args.first() == "version") WindowsBoxProcess.PINNED_CORE_VERSION else "" }, 0)
        try {
            tunnel.start(launch)
            withTimeout(10_000) { tunnel.state.first { it.status == TunnelStatus.RUNNING } }
            assertThat(tunnel.stop(waitForExit = true)).isFalse()
            assertThat(tunnel.hasOwnedProcess()).isTrue()
            assertThat(tunnel.captureRunningLaunch()).isNull()
            assertThat(tunnel.state.value.status).isEqualTo(TunnelStatus.FAILED)
            assertThat(runCatching { tunnel.start(Path.of("second.exe"), "second") }.exceptionOrNull()).isNotNull()
            assertThat(launches.get()).isEqualTo(1)
        } finally {
            fake.exit()
            assertThat(tunnel.stop(waitForExit = true)).isTrue()
        }
    }

    @Test
    fun stopDuringPreflightPreventsAnyLateTunCreationAndCannotOverwriteNewConfig() = runBlocking {
        val oldVersionEntered = CountDownLatch(1)
        val releaseOldVersion = CountDownLatch(1)
        val oldCheckCompleted = CountDownLatch(1)
        val launches = AtomicInteger()
        val fake = FakeProcess()
        var runningConfig: Path? = null
        val tunnel = WindowsBoxProcess(Files.createTempDirectory("lernet-fake-preflight"), { _, file ->
            launches.incrementAndGet()
            runningConfig = file
            fake
        }, { executable, args ->
            if (executable.fileName.toString() == "old.exe" && args.first() == "version") {
                oldVersionEntered.countDown()
                check(releaseOldVersion.await(10, TimeUnit.SECONDS))
            }
            if (executable.fileName.toString() == "old.exe" && args.first() == "check") oldCheckCompleted.countDown()
            if (args.first() == "version") WindowsBoxProcess.PINNED_CORE_VERSION else ""
        }, 0)
        try {
            tunnel.start(Path.of("old.exe"), "old configuration")
            check(oldVersionEntered.await(10, TimeUnit.SECONDS))
            assertThat(tunnel.captureRunningLaunch()).isNull()
            assertThat(tunnel.stop(waitForExit = true)).isTrue()
            tunnel.start(Path.of("new.exe"), "new configuration")
            withTimeout(10_000) { tunnel.state.first { it.status == TunnelStatus.RUNNING } }
            releaseOldVersion.countDown()
            check(oldCheckCompleted.await(10, TimeUnit.SECONDS))
            assertThat(launches.get()).isEqualTo(1)
            assertThat(Files.readString(requireNotNull(runningConfig))).isEqualTo("new configuration")
            assertThat(tunnel.captureRunningLaunch()?.config).isEqualTo("new configuration")
        } finally {
            releaseOldVersion.countDown()
            fake.exit()
            tunnel.stop(waitForExit = true)
        }
    }

    private fun fakeTunnel(fake: FakeProcess) = WindowsBoxProcess(
        Files.createTempDirectory("lernet-fake-restore"),
        { _, _ -> fake }, { _, args -> if (args.first() == "version") WindowsBoxProcess.PINNED_CORE_VERSION else "" }, 0
    )

    private class FakeProcess(private val terminateOnDestroy: Boolean = true, private val logInput: String = "") : Process() {
        private val closed = CountDownLatch(1)
        val logReadCompleted = CountDownLatch(1)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(logInput.toByteArray(Charsets.UTF_8)) {
            override fun close() {
                super.close()
                logReadCompleted.countDown()
            }
        }
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int {
            closed.await()
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !isAlive
        override fun exitValue(): Int {
            check(!isAlive)
            return 0
        }
        override fun destroy() {
            if (terminateOnDestroy) exit()
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = closed.count != 0L
        fun exit() {
            closed.countDown()
        }
    }
}
