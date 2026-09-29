package app.lernet.engine

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.Profile
import app.lernet.config.model.ProfileSource
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.live.ChannelHealth
import app.lernet.engine.live.ChannelWatch
import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveVia
import app.lernet.engine.live.SilenceReport
import app.lernet.engine.net.HopStop
import app.lernet.engine.net.HopTracer
import app.lernet.engine.net.OutboundDialer
import app.lernet.engine.net.OutboundEndpoint
import app.lernet.engine.net.VpnGuard
import app.lernet.engine.policy.ManualFailoverGroup
import app.lernet.engine.policy.ReconnectSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun startupProbeWaitsForSlowNetworkWithoutSlowingConnectedRefresh() = runTest(dispatcher) {
        val timeouts = mutableListOf<Int>()
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            dialer = OutboundDialer { _, timeout ->
                timeouts += timeout
                Result.success(Unit)
            },
        )

        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(timeouts).contains(30_000)
        engine.emit(EngineEvent.DnsAlive(answers = 1))
        runCurrent()

        testScheduler.advanceTimeBy(30_000)
        runCurrent()
        assertThat(timeouts).contains(8_000)
    }

    @Test
    fun tunnelHealthConfirmsTwoFailuresBeforeReconnecting() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        var checks = 0
        val controller = controller(
            this, engine, hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 60_000),
            tunnelHealthProbe = {
                checks++
                Result.failure(IllegalStateException("HTTP 504"))
            },
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()

        testScheduler.advanceTimeBy(5_000)
        runCurrent()
        assertThat(checks).isEqualTo(1)
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)

        testScheduler.advanceTimeBy(2_000)
        runCurrent()
        assertThat(checks).isEqualTo(2)
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isEqualTo(ConnectionCause.TunnelHealthFailed("HTTP 504"))
    }

    @Test
    fun tunnelHealthSuccessResetsFailureCount() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        var checks = 0
        val controller = controller(
            this, engine, hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 60_000),
            tunnelHealthProbe = {
                checks++
                if (checks == 2) Result.success(42L)
                else Result.failure(IllegalStateException("no reply"))
            },
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        engine.emit(EngineEvent.DnsAlive(answers = 1))
        runCurrent()

        testScheduler.advanceTimeBy(7_000)
        runCurrent()
        assertThat(checks).isEqualTo(2)
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)

        testScheduler.advanceTimeBy(5_000)
        runCurrent()
        assertThat(checks).isEqualTo(3)
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)

        testScheduler.advanceTimeBy(2_000)
        runCurrent()
        assertThat(checks).isEqualTo(4)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.TunnelHealthFailed::class.java)
    }

    @Test
    fun groupFailoverProbesAndLoadsTheSelectedProfile() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val first = sampleProfile()
        val second = first.copy(
            id = "p2",
            name = "резерв",
            selectedOutboundId = "out-2",
            outbounds = first.outbounds.map {
                it.copy(id = "out-2", singBoxJson = it.singBoxJson.replace("example.com", "backup.example.com"))
            },
        )
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        var selected: String? = null
        controller.setFailoverHandlers(
            resolve = { id -> if (id == "out-2") second to catchAllNodes().map { it.copy(profileId = "p2") } else null },
            onSelected = { selected = it },
        )
        controller.updateSettings(
            reconnect = ReconnectSettings(maxAttempts = 1),
            failoverEnabled = true,
            group = ManualFailoverGroup("g", "Резерв", listOf("out-1", "out-2")),
        )
        controller.connect(first, catchAllNodes(), RunMode.FULL_VPN,
            defaults = EngineDefaults(tunMtu = 1380, directDnsServer = "9.9.9.9"))
        runCurrent()
        controller.onEngineSignal(ConnectionCause.DialFailure("network down"))
        runCurrent()
        assertThat(selected).isEqualTo("p2")
        assertThat(controller.snapshot.value.activeProfileId).isEqualTo("p2")
        assertThat(controller.snapshot.value.activeOutboundId).isEqualTo("out-2")
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(controller.snapshot.value.banner?.groupName).isEqualTo("Резерв")
        assertThat(engine.startCount).isEqualTo(2)
        assertThat(engine.startedConfigs).hasSize(2)
        engine.startedConfigs.forEach { config ->
            assertThat(config).contains("\"mtu\":1380")
            assertThat(config).contains("9.9.9.9")
        }
    }

    @Test
    fun disconnectFromConnectedReachesDisconnectedWithinHardStopTimeout() = runTest(dispatcher) {
        val engine = RecordingBoxEngine(hangStop = true)
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        reachConnected(this, controller, engine)
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)

        controller.disconnect()
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(engine.stopCount).isEqualTo(1)
        assertThat(engine.abortCount).isEqualTo(0)

        testScheduler.advanceTimeBy(2_500)
        runCurrent()
        assertThat(engine.abortCount).isEqualTo(1)
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.DISCONNECTED)
    }

    @Test
    fun secondDisconnectWhileStoppingIsIgnoredSafely() = runTest(dispatcher) {
        val engine = RecordingBoxEngine(hangStop = true)
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        reachConnected(this, controller, engine)
        controller.disconnect()
        controller.disconnect()
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(engine.stopCount).isEqualTo(1)
    }

    @Test
    fun engineReadyWithZeroBytesBecomesConnected() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 20_000, connectTimeoutMs = 15_000),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        engine.emit(EngineEvent.Status(0, 0, 0, 0, 0))
        engine.emit(EngineEvent.DnsAlive(answers = 1))
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(engine.startCount).isEqualTo(1)

        testScheduler.advanceTimeBy(14_999)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(engine.startCount).isEqualTo(1)
    }

    @Test
    fun probeFailureRetriesWithoutClaimingConnected() = runTest(dispatcher) {
        val engine = RecordingBoxEngine(probeResult = Result.failure(IllegalStateException("reality handshake timeout")))
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 20_000, connectTimeoutMs = 15_000),
            l7UrlTestEnabled = true,
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.OutboundUnreachable::class.java)
        assertThat(controller.snapshot.value.cause?.technicalDetail()).contains("L7")
        assertThat(engine.startCount).isEqualTo(1)
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertThat(engine.startCount).isAtLeast(2)
    }

    @Test
    fun zeroTrafficAfterConnectedGoesReconnecting() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 3_000, connectTimeoutMs = 15_000),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        engine.emit(EngineEvent.Status(0, 0, 0, 0, 0))
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)

        testScheduler.advanceTimeBy(3_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.WatchdogTimeout::class.java)
        assertThat(controller.snapshot.value.cause?.titleRu()).isEqualTo("Нет трафика")
    }

    @Test
    fun watchdogDoesNotFireWhenDnsQueryOkDespiteZeroBytes() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 3_000, connectTimeoutMs = 15_000),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        engine.emit(EngineEvent.Status(0, 0, 0, 0, 0))
        engine.emit(EngineEvent.DnsAlive(answers = 3))
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)

        testScheduler.advanceTimeBy(3_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(engine.startCount).isEqualTo(1)
    }

    @Test
    fun staleTotalsWithoutGrowthLeaveConnected() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(
                maxAttempts = 5,
                initialBackoffMs = 1_000,
                backoffCapMs = 30_000,
                watchdogTimeoutMs = 3_000,
                connectTimeoutMs = 15_000,
            ),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        engine.emit(EngineEvent.Status(0, 0, 10_861, 13_202, 3))
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)

        testScheduler.advanceTimeBy(3_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(engine.startCount).isEqualTo(1)

        engine.emit(EngineEvent.Status(0, 0, 10_861, 13_202, 3))
        runCurrent()
        testScheduler.advanceTimeBy(3_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.WatchdogTimeout::class.java)

        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(engine.startCount).isEqualTo(2)

        engine.emit(EngineEvent.Status(0, 0, 0, 0, 0))
        runCurrent()
        testScheduler.advanceTimeBy(3_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.WatchdogTimeout::class.java)
        assertThat(engine.startCount).isEqualTo(2)
    }

    @Test
    fun l7GateOffConnectsOnTcpWithoutCallingUrlTest() = runTest(dispatcher) {
        val engine = RecordingBoxEngine(probeResult = Result.failure(IllegalStateException("must not run L7")))
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 20_000, connectTimeoutMs = 15_000),
            l7UrlTestEnabled = false,
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(engine.startCount).isEqualTo(1)
        assertThat(engine.probeCount).isEqualTo(0)
    }

    @Test
    fun tcpFailureRetriesWithoutCallingL7() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 20_000, connectTimeoutMs = 15_000),
            dialer = OutboundDialer { _, _ -> Result.failure(IllegalStateException("protected dial refused")) },
            l7UrlTestEnabled = false,
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.OutboundUnreachable::class.java)
        assertThat(controller.snapshot.value.cause?.technicalDetail()).contains("TCP")
        assertThat(engine.probeCount).isEqualTo(0)
        assertThat(engine.startCount).isEqualTo(1)
    }

    @Test
    fun protectFalseOnTcpProbeIsServiceRevoked() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 20_000, connectTimeoutMs = 15_000),
            dialer = OutboundDialer { _, _ ->
                Result.failure(Exception(VpnGuard.PROTECT_FALSE))
            },
            l7UrlTestEnabled = false,
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.FAILED)
        assertThat(controller.snapshot.value.cause).isEqualTo(ConnectionCause.ServiceRevoked)
        assertThat(engine.probeCount).isEqualTo(0)
    }

    @Test
    fun engineStartThrowableBecomesFailedWithoutReconnect() = runTest(dispatcher) {
        val engine = RecordingBoxEngine(startError = UnsatisfiedLinkError("dlopen failed"))
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 20_000, connectTimeoutMs = 15_000),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.FAILED)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.EngineStartFailed::class.java)
        assertThat(controller.snapshot.value.cause?.technicalDetail()).contains("dlopen failed")
        assertThat(engine.startCount).isEqualTo(1)
        testScheduler.advanceTimeBy(15_000)
        runCurrent()
        assertThat(engine.startCount).isEqualTo(1)
        assertThat(engine.stopCount).isEqualTo(1)
    }

    @Test
    fun retryStartThrowableBecomesFailedWithoutFurtherReconnect() = runTest(dispatcher) {
        val engine = RecordingBoxEngine(
            failOnStartNumber = 2,
            startError = UnsatisfiedLinkError("dlopen failed"),
        )
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 3_000, connectTimeoutMs = 15_000),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(engine.startCount).isEqualTo(1)

        testScheduler.advanceTimeBy(3_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)

        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.FAILED)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.EngineStartFailed::class.java)
        assertThat(engine.startCount).isEqualTo(2)
        testScheduler.advanceTimeBy(15_000)
        runCurrent()
        assertThat(engine.startCount).isEqualTo(2)
        assertThat(engine.stopCount).isEqualTo(1)
    }

    @Test
    fun proxyConnectReachesConnectedThenDisconnectStopsEngine() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.PROXY)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(controller.snapshot.value.mode).isEqualTo(RunMode.PROXY)
        assertThat(engine.startCount).isEqualTo(1)

        controller.disconnect()
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(engine.stopCount).isEqualTo(1)
    }

    @Test
    fun bytesWithoutDnsOkSoftReloadsAgain() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 60_000, connectTimeoutMs = 45_000),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        engine.emit(EngineEvent.Status(0, 0, 10861, 13202, 3))
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(controller.snapshot.value.dnsOk).isFalse()

        testScheduler.advanceTimeBy(8_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.DnsStalled::class.java)
        assertThat(controller.snapshot.value.cause?.titleRu()).isEqualTo("DNS не отвечает")

        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(engine.startCount).isEqualTo(2)

        engine.emit(EngineEvent.Status(0, 0, 200, 300, 1))
        runCurrent()
        testScheduler.advanceTimeBy(8_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.DnsStalled::class.java)
        assertThat(engine.startCount).isEqualTo(2)
    }

    @Test
    fun dnsOkAfterReconnectHoldsConnected() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 60_000, connectTimeoutMs = 45_000),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        engine.emit(EngineEvent.DnsAlive(answers = 2))
        runCurrent()
        testScheduler.advanceTimeBy(8_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(controller.snapshot.value.dnsOk).isTrue()
        assertThat(engine.startCount).isEqualTo(1)
    }

    @Test
    fun connectionsEventFillsLiveFeed() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        engine.emit(
            EngineEvent.Connections(
                reset = true,
                rows = listOf(
                    LiveConn(
                        id = "c1",
                        app = "org.telegram.messenger",
                        uid = 10123,
                        destHost = "149.154.167.91",
                        destPort = 443,
                        domain = "telegram.org",
                        outbound = "proxy",
                        via = LiveVia.PROXY,
                        uplink = 100,
                        downlink = 200,
                    ),
                ),
            ),
        )
        runCurrent()
        assertThat(controller.liveFeed.rows.value).hasSize(1)
        assertThat(controller.liveFeed.liveText()).contains("org.telegram.messenger")
        assertThat(controller.liveFeed.liveText()).contains("telegram.org")
        assertThat(controller.liveFeed.recordedText()).isEmpty()
        controller.liveFeed.setRecording(true)
        engine.emit(
            EngineEvent.Connections(
                reset = false,
                rows = listOf(
                    LiveConn(
                        id = "c1",
                        app = "org.telegram.messenger",
                        uid = 10123,
                        destHost = "149.154.167.91",
                        destPort = 443,
                        domain = "telegram.org",
                        outbound = "proxy",
                        via = LiveVia.PROXY,
                        uplink = 300,
                        downlink = 400,
                    ),
                ),
            ),
        )
        runCurrent()
        assertThat(controller.liveFeed.recordedText()).contains("org.telegram.messenger")
    }

    @Test
    fun statusTickKeepsPartialSilenceCounts() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        runCurrent()
        engine.emit(EngineEvent.Connections(reset = true, rows = listOf(proxyRow("a", 0), proxyRow("b", 20))))
        runCurrent()
        assertThat(controller.snapshot.value.channel).isEqualTo(ChannelHealth.PIPE_SILENT)
        assertThat(controller.snapshot.value.pipeSilentCount).isEqualTo(1)
        assertThat(controller.snapshot.value.pipeTunnelCount).isEqualTo(2)
        engine.emit(EngineEvent.Status(0, 0, 0, 0, 2))
        runCurrent()
        assertThat(controller.snapshot.value.pipeSilentCount).isEqualTo(1)
        assertThat(controller.snapshot.value.pipeTunnelCount).isEqualTo(2)
        assertThat(controller.snapshot.value.channel).isEqualTo(ChannelHealth.PIPE_SILENT)
    }

    @Test
    fun connectedStatusTickKeepsPartialSilence() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        reachConnected(this, controller, engine)
        engine.emit(EngineEvent.Connections(reset = true, rows = listOf(proxyRow("a", 0), proxyRow("b", 20))))
        runCurrent()
        val partial = controller.snapshot.value
        assertThat(partial.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(partial.channel).isEqualTo(ChannelHealth.PIPE_SILENT)
        assertThat(partial.pipeSilentCount).isEqualTo(1)
        assertThat(partial.pipeTunnelCount).isEqualTo(2)
        assertThat(ChannelWatch.silenceReport(partial.channel, partial.pipeSilentCount, partial.pipeTunnelCount))
            .isEqualTo(SilenceReport.PARTIAL)
        engine.emit(EngineEvent.Status(64, 128, 64, 128, 2))
        runCurrent()
        val after = controller.snapshot.value
        assertThat(after.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(after.channel).isEqualTo(ChannelHealth.PIPE_SILENT)
        assertThat(after.pipeSilentCount).isEqualTo(1)
        assertThat(after.pipeTunnelCount).isEqualTo(2)
        assertThat(ChannelWatch.silenceReport(after.channel, after.pipeSilentCount, after.pipeTunnelCount))
            .isEqualTo(SilenceReport.PARTIAL)
    }

    @Test
    fun connectedStatusTickKeepsFullSilence() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        reachConnected(this, controller, engine)
        engine.emit(EngineEvent.Connections(reset = true, rows = listOf(proxyRow("a", 0), proxyRow("b", 0))))
        runCurrent()
        val dead = controller.snapshot.value
        assertThat(dead.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(dead.channel).isEqualTo(ChannelHealth.TUNNEL_DEAD)
        assertThat(dead.pipeSilentCount).isEqualTo(2)
        assertThat(dead.pipeTunnelCount).isEqualTo(2)
        assertThat(ChannelWatch.silenceReport(dead.channel, dead.pipeSilentCount, dead.pipeTunnelCount))
            .isEqualTo(SilenceReport.ALL)
        engine.emit(EngineEvent.Status(0, 0, 0, 0, 2))
        runCurrent()
        val after = controller.snapshot.value
        assertThat(after.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(after.channel).isEqualTo(ChannelHealth.TUNNEL_DEAD)
        assertThat(after.pipeSilentCount).isEqualTo(2)
        assertThat(after.pipeTunnelCount).isEqualTo(2)
        assertThat(ChannelWatch.silenceReport(after.channel, after.pipeSilentCount, after.pipeTunnelCount))
            .isEqualTo(SilenceReport.ALL)
    }

    @Test
    fun connectDoesNotAwaitSlowHopWalkBeforeConnected() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val tracer = SlowHopTracer(delayMs = 60_000)
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(
                maxAttempts = 1,
                watchdogTimeoutMs = 120_000,
                connectTimeoutMs = 120_000,
            ),
        )
        controller.pathTracer = tracer
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(controller.snapshot.value.hopRunning).isTrue()
        assertThat(tracer.started).isTrue()
        assertThat(tracer.finished).isFalse()
        assertThat(controller.snapshot.value.hops).isEmpty()
    }

    @Test
    fun hopWalkPublishesPartialsWithoutBlockingConnect() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val tracer = ProgressiveHopTracer()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(
                maxAttempts = 1,
                watchdogTimeoutMs = 120_000,
                connectTimeoutMs = 120_000,
            ),
        )
        controller.pathTracer = tracer
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(controller.snapshot.value.hopRunning).isTrue()
        assertThat(controller.snapshot.value.hops).hasSize(1)
        assertThat(controller.snapshot.value.hops[0].timedOut).isTrue()
        testScheduler.advanceTimeBy(10)
        runCurrent()
        assertThat(controller.snapshot.value.hops).hasSize(2)
        assertThat(controller.snapshot.value.hops[1].address).isEqualTo("10.0.0.1")
        assertThat(controller.snapshot.value.hopRunning).isTrue()
        testScheduler.advanceTimeBy(10)
        runCurrent()
        assertThat(controller.snapshot.value.hopRunning).isFalse()
        assertThat(controller.snapshot.value.hops).hasSize(2)
    }

    @Test
    fun connectTracesTheDialTargetOnceUntilManualRefresh() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val tracer = CountingHopTracer()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(
                maxAttempts = 1,
                watchdogTimeoutMs = 120_000,
                connectTimeoutMs = 120_000,
            ),
        )
        controller.pathTracer = tracer
        reachConnected(this, controller, engine)
        val hops = controller.snapshot.value.hops
        assertThat(tracer.calls).isEqualTo(1)
        // Instant tracer finished before TCP (timedOut), then TCP seals a synthetic dest.
        assertThat(hops).hasSize(2)
        assertThat(hops[0].address).isEqualTo("example.com")
        assertThat(hops[0].timedOut).isTrue()
        assertThat(hops[1].synthetic).isTrue()
        assertThat(hops[1].timedOut).isFalse()
        assertThat(controller.snapshot.value.hopChecked).isTrue()

        testScheduler.advanceTimeBy(7_000)
        runCurrent()
        assertThat(tracer.calls).isEqualTo(1)
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTED)

        controller.refreshHop()
        runCurrent()
        assertThat(tracer.calls).isEqualTo(2)
        assertThat(controller.snapshot.value.hops.single().timedOut).isFalse()
    }

    @Test
    fun failedTcpProbeRetriesAndRecordsOnePath() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val tracer = CountingHopTracer()
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            dialer = OutboundDialer { _, _ -> Result.failure(IllegalStateException("refused")) },
        )
        controller.pathTracer = tracer
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(tracer.calls).isAtLeast(1)
        assertThat(controller.snapshot.value.hops.single().timedOut).isTrue()
        assertThat(controller.snapshot.value.hops.single().country).isNull()
    }

    @Test
    fun selectedServerCanBeTracedWithoutConnecting() = runTest(dispatcher) {
        val engine = RecordingBoxEngine()
        val tracer = CountingHopTracer()
        val controller = controller(this, engine, hardStopTimeoutMs = 2_500)
        controller.pathTracer = tracer

        controller.previewHop(OutboundEndpoint("example.com", 443))
        runCurrent()

        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(controller.snapshot.value.hopHost).isEqualTo("example.com")
        assertThat(controller.snapshot.value.hopChecked).isTrue()
        assertThat(tracer.calls).isEqualTo(1)
        assertThat(engine.startCount).isEqualTo(0)
    }

    @Test
    fun connectTimeoutStartsReconnect() = runTest(dispatcher) {
        val engine = RecordingBoxEngine(hangStart = true)
        val controller = controller(
            this,
            engine,
            hardStopTimeoutMs = 2_500,
            reconnect = ReconnectSettings(maxAttempts = 3, watchdogTimeoutMs = 3_000, connectTimeoutMs = 15_000),
        )
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTING)

        testScheduler.advanceTimeBy(3_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.CONNECTING)
        assertThat(engine.startCount).isEqualTo(1)

        testScheduler.advanceTimeBy(12_000)
        runCurrent()
        assertThat(controller.snapshot.value.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(controller.snapshot.value.cause).isInstanceOf(ConnectionCause.ConnectTimeout::class.java)
        assertThat(engine.startCount).isEqualTo(1)
    }

    private suspend fun reachConnected(
        scope: TestScope,
        controller: ConnectionController,
        engine: RecordingBoxEngine,
    ) {
        controller.connect(sampleProfile(), catchAllNodes(), RunMode.FULL_VPN)
        scope.runCurrent()
        check(controller.snapshot.value.state == ConnectionState.CONNECTED) {
            "expected CONNECTED after engine start, was ${controller.snapshot.value.state}"
        }
        engine.emit(EngineEvent.Status(64, 128, 64, 128, 1))
        scope.runCurrent()
        check(controller.snapshot.value.state == ConnectionState.CONNECTED) {
            "expected CONNECTED, was ${controller.snapshot.value.state}"
        }
    }

    private fun controller(
        scope: TestScope,
        engine: RecordingBoxEngine,
        hardStopTimeoutMs: Long,
        reconnect: ReconnectSettings = ReconnectSettings(maxAttempts = 1, watchdogTimeoutMs = 60_000),
        dialer: OutboundDialer = OutboundDialer { _, _ -> Result.success(Unit) },
        l7UrlTestEnabled: Boolean = false,
        tunnelHealthProbe: (suspend () -> Result<Long>)? = null,
    ): ConnectionController {
        val controller = ConnectionController(
            engine = engine,
            scope = scope.backgroundScope,
            hardStopTimeoutMs = hardStopTimeoutMs,
            outboundDialer = dialer,
            l7UrlTestEnabled = l7UrlTestEnabled,
            tunnelHealthProbe = tunnelHealthProbe,
            tunnelHealthIntervalMs = { 5_000L },
            pathDispatcher = dispatcher,
        )
        controller.updateSettings(reconnect, failoverEnabled = false, group = null)
        return controller
    }

    private fun proxyRow(id: String, downlink: Long): LiveConn = LiveConn(
        id = id,
        app = "app.example",
        uid = 1,
        destHost = "1.1.1.1",
        destPort = 443,
        domain = "example.com",
        outbound = "proxy",
        via = LiveVia.PROXY,
        uplink = 10,
        downlink = downlink,
    )

    private fun sampleProfile(): Profile =
        Profile(
            id = "p1",
            name = "тест",
            createdAtEpochMs = 0,
            updatedAtEpochMs = 0,
            source = ProfileSource.JSON_PASTE,
            selectedOutboundId = "out-1",
            outbounds = listOf(
                NormalizedOutbound(
                    id = "out-1",
                    tag = "proxy",
                    type = "vless",
                    singBoxJson = """{"type":"vless","tag":"proxy","server":"example.com",""" +
                        """"server_port":443,"uuid":"11111111-1111-1111-1111-111111111111"}""",
                ),
            ),
            subscriptionUrl = null,
            lastRefreshEpochMs = null,
        )

    private fun catchAllNodes(): List<RuleNodeRecord> =
        listOf(
            RuleNodeRecord(
                id = "root",
                profileId = "p1",
                parentId = null,
                enabled = true,
                sortIndex = 0,
                action = "proxy",
                apps = emptyList(),
                domains = emptyList(),
                domainSuffixes = emptyList(),
                ipCidrs = emptyList(),
                geoip = emptyList(),
            ),
        )
}

private class CountingHopTracer : HopTracer {
    var calls: Int = 0

    override suspend fun trace(target: OutboundEndpoint, dialOk: Boolean): List<HopStop> {
        calls += 1
        return listOf(HopStop(address = target.host, country = null, timedOut = !dialOk))
    }
}

/** Blocks for [delayMs] so Connect must not join the hop job to reach Connected. */
private class SlowHopTracer(private val delayMs: Long) : HopTracer {
    @Volatile
    var started: Boolean = false

    @Volatile
    var finished: Boolean = false

    override suspend fun trace(target: OutboundEndpoint, dialOk: Boolean): List<HopStop> {
        started = true
        delay(delayMs)
        finished = true
        return listOf(HopStop(address = target.host, country = null, timedOut = !dialOk))
    }
}

/** Emits one hop per short delay — progressive strip updates. */
private class ProgressiveHopTracer : HopTracer {
    override suspend fun trace(target: OutboundEndpoint, dialOk: Boolean): List<HopStop> =
        error("use traceLive")

    override suspend fun traceLive(
        target: OutboundEndpoint,
        dialOk: () -> Boolean,
        onPartial: suspend (List<HopStop>) -> Unit,
    ): List<HopStop> {
        val first = listOf(HopStop(address = null, country = null, timedOut = true))
        onPartial(first)
        delay(10)
        val second = first + HopStop(address = "10.0.0.1", country = "ru", timedOut = false)
        onPartial(second)
        delay(10)
        return second
    }
}

private class RecordingBoxEngine(
    private val hangStop: Boolean = false,
    private val hangStart: Boolean = false,
    private val startError: Throwable? = null,
    private val failOnStartNumber: Int? = null,
    private val probeResult: Result<Int> = Result.success(0),
) : BoxEngine {
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    var startCount: Int = 0
    var stopCount: Int = 0
    var abortCount: Int = 0
    var probeCount: Int = 0
    val startedConfigs = mutableListOf<String>()

    override val isNativeAvailable: Boolean = true
    override val engineVersion: String = "fake"
    override val events: Flow<EngineEvent> = _events.asSharedFlow()

    override suspend fun start(compiledJson: String, mode: RunMode) {
        startCount += 1
        startedConfigs += compiledJson
        val shouldFail = when (failOnStartNumber) {
            null -> startError != null
            else -> startCount == failOnStartNumber
        }
        if (shouldFail) {
            throw startError ?: error("start failed")
        }
        if (hangStart) {
            delay(60_000)
            return
        }
        _events.emit(EngineEvent.Started)
    }

    override suspend fun stop() {
        stopCount += 1
        if (hangStop) {
            delay(60_000)
        }
    }

    override fun abort() {
        abortCount += 1
    }

    override suspend fun probeOutbound(tag: String, url: String, timeoutMs: Int): Result<Int> {
        probeCount += 1
        return probeResult
    }

    suspend fun emit(event: EngineEvent) {
        _events.emit(event)
    }
}
