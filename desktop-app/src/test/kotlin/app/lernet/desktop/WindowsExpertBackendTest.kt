package app.lernet.desktop

import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.ExpertBackendEvent
import app.lernet.engine.policy.ExpertStateUncertainException
import app.lernet.engine.policy.PolicyAssembledConfig
import app.lernet.engine.policy.PolicyPhysicalExit
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.PolicyProgram
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake process and loopback protocol server; no application, service, driver or TUN is created. */
class WindowsExpertBackendTest {
    @Test
    fun `native protocol ready does not hide failed end to end health`() {
        fun status(phase: String, health: String) = Json.parseToJsonElement("""{"phase":"$phase","health":"$health"}""").jsonObject
        assertEquals(ExitPhase.DEGRADED, nativeExitPhase(status("ready", "degraded")))
        assertEquals(ExitPhase.READY, nativeExitPhase(status("ready", "healthy")))
        assertEquals(ExitPhase.SLEEPING, nativeExitPhase(status("sleeping", "degraded")))
        assertEquals(ExitPhase.DRAINING, nativeExitPhase(status("stopping", "healthy")))
        assertEquals(ExitPhase.FAILED, nativeExitPhase(status("unsupported", "healthy")))
        val malformed = Json.parseToJsonElement("""{"phase":{"password":"private-native-secret"},"health":[]}""")
        assertEquals(ExitPhase.FAILED, nativeExitPhase(malformed.jsonObject))
    }

    @Test
    fun `malformed optional native rows preserve acknowledged tunnel and valid observations`() = runBlocking {
        val physical = PolicyPhysicalExit("known", "profile", emptyList(), ExitLifecyclePolicy())
        Fixture(physicalExits = listOf(physical)).use { fixture ->
            fixture.statusFields = """
                "running":true,"network_epoch":{"password":"private-native-secret"},
                "underlay_interface":["private-native-secret"],
                "exits":[null,{"tag":{"password":"private-native-secret"}},
                  {"tag":"known","phase":"ready","health":[],"reason":{"password":"private-native-secret"},
                   "latency_ms":"12","active_flows":[],"pending_flows":-1,"last_check_ms":true}],
                "folders":[{"tag":[],"selected_tag":{"password":"private-native-secret"}}],
                "flows":[{"id":17,"destination":"invalid.example:443"},
                  {"id":"invalid-destination","destination":["private-native-secret"]},
                  {"id":"flow","destination":"example.invalid:443","selected_outbound":"known","network":[],
                   "process":{"password":"private-native-secret"},"state":true,"reason":{"password":"private-native-secret"},
                   "node_ids":["valid-node",{},[],null,17,true],"upload_bytes":-1,"download_bytes":"55",
                   "closed":"false","started_ms":false,"revision":"1"},
                  {"id":"unknown","destination":"other.invalid:443","outbound":"private-native-secret"}]
            """.trimIndent()
            val queue = Channel<ExpertBackendEvent>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.backend.events.collect { queue.send(it) }
            }
            try {
                fixture.backend.negotiateCapabilities()
                val started = fixture.backend.start(PolicyProgram(1, emptyList()))
                val seen = mutableListOf<ExpertBackendEvent>()
                withTimeout(5_000) {
                    while (seen.filterIsInstance<ExpertBackendEvent.Observation>().size < 2 ||
                        seen.none { it is ExpertBackendEvent.ExitsSnapshot }
                    ) {
                        val event = queue.receive()
                        assertFalse(event is ExpertBackendEvent.TunnelLost)
                        seen += event
                    }
                }
                assertEquals(started.identity, fixture.backend.identity)
                assertTrue(fixture.process!!.isAlive)
                val exits = seen.filterIsInstance<ExpertBackendEvent.ExitsSnapshot>().first()
                assertEquals(listOf(physical.key), exits.exits.map { it.key })
                assertEquals(null, exits.exits.single().latencyMs)
                assertEquals(0, exits.exits.single().activeFlows)
                val observations = seen.filterIsInstance<ExpertBackendEvent.Observation>().map { it.connection }
                val flow = observations.first { it.id == "flow" }
                assertEquals(listOf("valid-node"), flow.nodeIds)
                assertEquals(null, flow.application)
                assertEquals("?", flow.protocol)
                assertEquals(0L, flow.uploadedBytes)
                assertEquals(0L, flow.downloadedBytes)
                assertEquals(null, flow.active)
                assertEquals(null, flow.policyRevision)
                assertEquals("Через другой выход", observations.first { it.id == "unknown" }.decision)
                assertFalse(seen.toString().contains("private-native-secret"))
            } finally {
                collector.cancelAndJoin()
                queue.close()
            }
        }
    }

    @Test
    fun `route conflict never publishes a running expert tunnel`() = runBlocking {
        Fixture(routeCheck = { "Windows выбрала другой VPN" }).use { fixture ->
            fixture.backend.negotiateCapabilities()
            val failure = runCatching { fixture.backend.start(PolicyProgram(1, emptyList())) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("другой VPN"))
            assertEquals(null, fixture.backend.identity)
            assertFalse(fixture.process!!.isAlive)
            assertEquals(1, fixture.releases.get())
        }
    }

    @Test
    fun `guardian permit failure cannot publish a running identity`() = runBlocking {
        Fixture(permit = { error("Разрешение общего TUN не подтверждено") }).use { fixture ->
            fixture.backend.negotiateCapabilities()
            val failure = runCatching { fixture.backend.start(PolicyProgram(1, emptyList())) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("не подтверждено"))
            assertEquals(null, fixture.backend.identity)
            assertEquals(null, fixture.backend.assembled)
            assertFalse(fixture.process!!.isAlive)
            assertEquals(1, fixture.permits.get())
            assertEquals(1, fixture.beforeStops.get())
            assertEquals(1, fixture.releases.get())
        }
    }

    @Test
    fun `stop during guardian permit fences publication and preserves next owner`() = runBlocking {
        val entered = CountDownLatch(1)
        val continuePermit = CountDownLatch(1)
        Fixture(permit = { ack ->
            if (ack.revision == 1L) {
                entered.countDown()
                check(continuePermit.await(10, TimeUnit.SECONDS))
            }
        }).use { fixture ->
            fixture.backend.negotiateCapabilities()
            val start = async(Dispatchers.IO) { runCatching { fixture.backend.start(PolicyProgram(1, emptyList())) } }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val oldProcess = fixture.process!!
                val generation = fixture.backend.operationGeneration
                val stop = async(Dispatchers.IO) { fixture.backend.stop(null) }
                withTimeout(5_000) {
                    while (fixture.backend.operationGeneration == generation) kotlinx.coroutines.delay(10)
                }
                assertEquals(null, fixture.backend.identity)
                continuePermit.countDown()
                withTimeout(10_000) { stop.await() }
                assertTrue(withTimeout(10_000) { start.await() }.isFailure)
                assertFalse(oldProcess.isAlive)
                assertEquals(null, fixture.backend.identity)
                assertEquals(1, fixture.beforeStops.get())
                assertEquals(1, fixture.releases.get())
                fixture.backend.negotiateCapabilities()
                val successor = fixture.process!!
                val next = fixture.backend.start(PolicyProgram(2, emptyList()))
                assertTrue(successor.isAlive)
                assertEquals(next.identity, fixture.backend.identity)
                assertEquals(2, fixture.permits.get())
                assertEquals(1, fixture.beforeStops.get())
                assertEquals(1, fixture.releases.get())
            } finally {
                continuePermit.countDown()
            }
        }
    }

    @Test
    fun `hot policy update preserves one owned process and acknowledged interface`() = runBlocking {
        Fixture().use { fixture ->
            fixture.backend.negotiateCapabilities()
            val started = fixture.backend.start(PolicyProgram(1, emptyList()))
            val applied = fixture.backend.apply(PolicyProgram(2, emptyList()), started.identity)
            assertEquals(started.identity, applied.identity)
            assertEquals(2, applied.revision)
            assertEquals(1, fixture.spawned.get())
            assertEquals(1, fixture.reservations.get())
            fixture.backend.stop(applied.identity)
            assertEquals(1, fixture.releases.get())
            assertFalse(fixture.process!!.isAlive)
        }
    }

    @Test
    fun `precommit rejection retains acknowledged running generation`() = runBlocking {
        Fixture().use { fixture ->
            fixture.backend.negotiateCapabilities()
            val started = fixture.backend.start(PolicyProgram(1, emptyList()))
            fixture.applyMode = "reject"
            val failure = runCatching { fixture.backend.apply(PolicyProgram(2, emptyList()), started.identity) }.exceptionOrNull()
            assertTrue(failure is ExpertControlRejectedException)
            assertEquals(1, fixture.backend.assembled!!.program.revision)
            assertTrue(fixture.process!!.isAlive)
            assertEquals(0, fixture.releases.get())
        }
    }

    @Test
    fun `lost apply response reconciles actual live revision`() = runBlocking {
        Fixture().use { fixture ->
            fixture.backend.negotiateCapabilities()
            val started = fixture.backend.start(PolicyProgram(1, emptyList()))
            fixture.applyMode = "lost-response"
            val applied = fixture.backend.apply(PolicyProgram(2, emptyList()), started.identity)
            assertEquals(started.identity, applied.identity)
            assertEquals(2, applied.revision)
            assertTrue(fixture.process!!.isAlive)
        }
    }

    @Test
    fun `stopped native status cannot acknowledge lost apply response`() = runBlocking {
        Fixture().use { fixture ->
            fixture.backend.negotiateCapabilities()
            val started = fixture.backend.start(PolicyProgram(1, emptyList()))
            fixture.applyMode = "stopped"
            val failure = runCatching { fixture.backend.apply(PolicyProgram(2, emptyList()), started.identity) }.exceptionOrNull()
            assertTrue(failure is ExpertStateUncertainException)
            assertEquals(null, fixture.backend.identity)
            assertFalse(fixture.process!!.isAlive)
            assertEquals(1, fixture.releases.get())
        }
    }

    @Test
    fun `string running flag cannot confirm lost apply publication`() = runBlocking {
        Fixture().use { fixture ->
            fixture.backend.negotiateCapabilities()
            val started = fixture.backend.start(PolicyProgram(1, emptyList()))
            fixture.applyMode = "lost-response"
            fixture.statusFields = "\"running\":\"true\""
            val failure = runCatching { fixture.backend.apply(PolicyProgram(2, emptyList()), started.identity) }.exceptionOrNull()
            assertTrue(failure is ExpertStateUncertainException)
            assertEquals(null, fixture.backend.identity)
            assertFalse(fixture.process!!.isAlive)
            assertFalse(failure?.message.orEmpty().contains("private-native-secret"))
        }
    }

    @Test
    fun `stop during binary preparation fences later process creation`() = runBlocking {
        val entered = CountDownLatch(1)
        val continuePreparation = CountDownLatch(1)
        Fixture(prepare = { directory ->
            entered.countDown()
            check(continuePreparation.await(5, TimeUnit.SECONDS))
            directory.resolve("lernet-core.exe")
        }).use { fixture ->
            val negotiate = async(Dispatchers.IO) { runCatching { fixture.backend.negotiateCapabilities() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val generation = fixture.backend.operationGeneration
            val stop = async(Dispatchers.IO) { fixture.backend.stop(null) }
            // Stop records its cancellation before waiting for the short native lifecycle critical section.
            withTimeout(5_000) {
                while (fixture.backend.operationGeneration == generation) kotlinx.coroutines.delay(10)
                continuePreparation.countDown()
                stop.await()
                assertTrue(negotiate.await().isFailure)
            }
            assertEquals(0, fixture.spawned.get())
            assertEquals(0, fixture.reservations.get())
        }
    }

    @Test
    fun `late failed start cannot stop the next negotiated process`() = runBlocking {
        Fixture().use { fixture ->
            fixture.backend.negotiateCapabilities()
            fixture.blockedOperation = "start"
            val oldStart = async(Dispatchers.IO) { runCatching { fixture.backend.start(PolicyProgram(1, emptyList())) } }
            try {
                assertTrue(fixture.requestEntered.await(10, TimeUnit.SECONDS))
                val oldProcess = fixture.process!!
                fixture.backend.stop(null)
                assertFalse(oldProcess.isAlive)
                fixture.blockedOperation = null
                fixture.backend.negotiateCapabilities()
                val successor = fixture.process!!
                val generation = fixture.backend.operationGeneration
                fixture.continueRequest.countDown()
                assertTrue(withTimeout(10_000) { oldStart.await() }.isFailure)
                assertTrue(successor.isAlive)
                assertEquals(generation, fixture.backend.operationGeneration)
                assertEquals(1, fixture.releases.get())
                assertEquals(1, fixture.beforeStops.get())
                val started = fixture.backend.start(PolicyProgram(2, emptyList()))
                assertEquals(started.identity, fixture.backend.identity)
                assertEquals(2, fixture.spawned.get())
            } finally {
                fixture.continueRequest.countDown()
            }
        }
    }

    @Test
    fun `late uncertain apply cannot stop the next running process`() = runBlocking {
        Fixture().use { fixture ->
            fixture.backend.negotiateCapabilities()
            val first = fixture.backend.start(PolicyProgram(1, emptyList()))
            fixture.blockedOperation = "apply"
            val oldApply = async(Dispatchers.IO) {
                runCatching { fixture.backend.apply(PolicyProgram(2, emptyList()), first.identity) }
            }
            try {
                assertTrue(fixture.requestEntered.await(10, TimeUnit.SECONDS))
                val oldProcess = fixture.process!!
                fixture.backend.stop(first.identity)
                assertFalse(oldProcess.isAlive)
                fixture.blockedOperation = null
                fixture.backend.negotiateCapabilities()
                val successor = fixture.process!!
                val next = fixture.backend.start(PolicyProgram(3, emptyList()))
                val generation = fixture.backend.operationGeneration
                fixture.continueRequest.countDown()
                assertTrue(withTimeout(10_000) { oldApply.await() }.exceptionOrNull() is ExpertStateUncertainException)
                assertTrue(successor.isAlive)
                assertEquals(next.identity, fixture.backend.identity)
                assertEquals(3, fixture.backend.assembled!!.program.revision)
                assertEquals(generation, fixture.backend.operationGeneration)
                assertEquals(1, fixture.releases.get())
                assertEquals(1, fixture.beforeStops.get())
                assertEquals(2, fixture.spawned.get())
            } finally {
                fixture.continueRequest.countDown()
            }
        }
    }

    private class Fixture(
        private val prepare: ((Path) -> Path)? = null,
        private val routeCheck: (ExpertNativeAck) -> String? = { null },
        private val permit: (ExpertNativeAck) -> Unit = {},
        private val physicalExits: List<PolicyPhysicalExit> = emptyList(),
    ) : AutoCloseable {
        private val directory = Files.createTempDirectory("lernet-expert-fake-")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val executor = Executors.newCachedThreadPool { action -> Thread(action, "expert-fake-http").apply { isDaemon = true } }
        val spawned = AtomicInteger()
        val reservations = AtomicInteger()
        val releases = AtomicInteger()
        val beforeStops = AtomicInteger()
        val permits = AtomicInteger()
        var process: FakeProcess? = null
        val requestEntered = CountDownLatch(1)
        val continueRequest = CountDownLatch(1)

        @Volatile var blockedOperation: String? = null

        @Volatile var applyMode = "normal"

        @Volatile var statusFields: String? = null

        @Volatile private var running = false

        @Volatile private var revision = 0L

        @Volatile private var token = ""
        val backend: WindowsExpertBackend

        init {
            server.createContext("/lernet/v1") { exchange ->
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") {
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return@createContext
                }
                val operation = exchange.requestURI.path.substringAfterLast('/')
                val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }.takeIf { it.isNotBlank() }
                    ?.let { Json.parseToJsonElement(it) as JsonObject }
                if (operation == blockedOperation) {
                    requestEntered.countDown()
                    check(continueRequest.await(20, TimeUnit.SECONDS))
                    val rejected = """{"error":"old_operation_failed"}""".toByteArray()
                    exchange.sendResponseHeaders(500, rejected.size.toLong())
                    exchange.responseBody.use { it.write(rejected) }
                    return@createContext
                }
                var status = 200
                val body = when (operation) {
                    "capabilities" ->
                        """{"protocol_version":1,"preserves_tun":true,"hot_policy_apply":true,
                        "atomic_prepare_commit":true,"destination_redirect":true,"independent_exit_lifecycle":true,
                        "bounded_first_flow_wait":true}"""
                    "start" -> {
                        running = true
                        revision = request!!["revision"]!!.jsonPrimitive.content.toLong()
                        ack()
                    }
                    "apply" -> when (applyMode) {
                        "reject" -> {
                            status = 422
                            """{"error":"policy_start_failed"}"""
                        }
                        "lost-response", "stopped" -> {
                            revision = request!!["revision"]!!.jsonPrimitive.content.toLong()
                            if (applyMode == "stopped") running = false
                            status = 500
                            """{"error":"response_lost"}"""
                        }
                        else -> {
                            revision = request!!["revision"]!!.jsonPrimitive.content.toLong()
                            ack()
                        }
                    }
                    "status" -> ack().dropLast(1) + "," +
                        (statusFields ?: "\"running\":$running,\"exits\":[],\"flows\":[],\"folders\":[]") + "}"
                    else -> """{"ok":true}"""
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.executor = executor
            server.start()
            backend = WindowsExpertBackend(
                object : WindowsExpertOwner {
                    override fun workspaceDirectory() = directory
                    override fun reserveExpertMode() {
                        reservations.incrementAndGet()
                    }
                    override fun releaseExpertMode(restorePrevious: Boolean) {
                        releases.incrementAndGet()
                    }
                },
                scope, assemble = { program ->
                    PolicyAssembledConfig(
                        "{}", """{"inbounds":[{"type":"tun","interface_name":"LerNET-fixture"}]}""", "{}", "{}",
                        program, physicalExits, emptyList(),
                    )
                },
                probeUrl = { "https://health.example/204" }, log = {}, elevated = { true },
                prepareBinary = { prepare?.invoke(it) ?: it.resolve("lernet-core.exe") }, verifyRoutes = routeCheck,
                beforeStop = { beforeStops.incrementAndGet() },
                permitIngress = { ack, _ ->
                    permits.incrementAndGet()
                    permit(ack)
                },
                prepareControlSecret = { core, value ->
                    check(core.startsWith(directory))
                    val privateDirectory = Files.createTempDirectory(directory, "fake-control-")
                    privateDirectory.resolve("control-token").also { Files.writeString(it, value) }
                }, spawn = { args, _ ->
                    spawned.incrementAndGet()
                    assertTrue(args[args.indexOf("--owner-started-ms") + 1].toLong() > 0)
                    assertEquals("--token-file", args[args.lastIndex - 1])
                    token = Files.readString(Path.of(args.last()))
                    FakeProcess("LERNET_CONTROL_READY {\"port\":${server.address.port},\"protocol_version\":1}\n").also {
                        process = it
                    }
                }
            )
        }

        private fun ack() = """{"instance_id":"instance","interface_id":"lernet:17:7","revision":$revision}"""

        override fun close() {
            runBlocking { backend.stop(null) }
            scope.cancel()
            server.stop(0)
            executor.shutdownNow()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private class FakeProcess(ready: String) : Process() {
        private val alive = AtomicBoolean(true)
        private val input = ByteArrayInputStream(ready.toByteArray())
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int {
            alive.set(false)
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive.get()
        override fun exitValue(): Int {
            check(!alive.get())
            return 0
        }
        override fun destroy() {
            alive.set(false)
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = alive.get()
    }
}
