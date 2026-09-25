package app.lernet.engine

import android.content.Context
import app.lernet.BuildConfig
import app.lernet.engine.compile.AssembledKeys
import app.lernet.engine.compile.ConfigAssembler
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.redact.LerNetLog
import app.lernet.vpn.EngineProcessHost
import app.lernet.vpn.LibboxCommandHandler
import app.lernet.vpn.LibboxNative
import app.lernet.vpn.LibboxPlatformRegistry
import app.lernet.vpn.LibboxStatusClient
import dagger.hilt.android.qualifiers.ApplicationContext
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.HTTPHeaders
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class LibboxBoxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val host: EngineProcessHost,
) : BoxEngine {
    private val mutex = Mutex()
    private val generation = AtomicInteger(0)
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)

    @Volatile
    private var server: CommandServer? = null
    private var statusClient: LibboxStatusClient? = null
    private var setupDone = false

    override val isNativeAvailable: Boolean = true

    override val engineVersion: String
        get() = LibboxNative.loadedVersion ?: LibboxNative.PINNED_VERSION

    override val events: Flow<EngineEvent> = _events.asSharedFlow()

    override suspend fun start(compiledJson: String, mode: RunMode) {
        runCatching { startGuarded(compiledJson, mode) }
            .onFailure { error ->
                val message = error.message ?: error.toString()
                LerNetLog.e(TAG, "start escaped: $message", error)
                CrashTrail.recordFailure("engine.start", error)
                runCatching { host.stop() }
                emitFailed(EngineErrorMapper.map(message))
            }
    }

    private suspend fun startGuarded(compiledJson: String, mode: RunMode) {
        if (compiledJson.isBlank()) {
            emitFailed(ConnectionCause.InvalidConfig(listOf("пустой compiled JSON")))
            return
        }
        val gen = generation.get()
        mutex.withLock {
            if (generation.get() != gen) {
                LerNetLog.w(TAG, "start aborted: stop requested")
                return
            }
            runCatching {
                withContext(Dispatchers.IO) { startLocked(compiledJson, mode) }
            }.onFailure { error ->
                closeStatusClient()
                closeServerLocked()
                host.stop()
                val message = error.message ?: error.toString()
                LerNetLog.e(TAG, "libbox start failed: $message", error)
                CrashTrail.recordFailure("engine.startLocked", error)
                emitFailed(EngineErrorMapper.map(message))
                return
            }
        }
        if (generation.get() == gen) {
            emitStarted()
            attachStatusClient()
        } else {
            LerNetLog.w(TAG, "start finished after stop; not claiming ready")
            abort()
        }
    }

    override suspend fun stop() {
        generation.incrementAndGet()
        val closed = withTimeoutOrNull(GRACEFUL_CLOSE_MS) {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    closeStatusClient()
                    closeServerLocked()
                }
            }
        }
        if (closed == null) {
            LerNetLog.e(TAG, "graceful libbox close timed out after ${GRACEFUL_CLOSE_MS}ms")
        }
        host.stop()
    }

    override fun abort() {
        host.stop()
    }

    override suspend fun probeOutbound(tag: String, url: String, timeoutMs: Int): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val running = server
                if (running == null) {
                    CrashTrail.mark("L7 skipped: command server not started")
                    return@withContext Result.failure(IllegalStateException("command server not started"))
                }
                CrashTrail.mark("before probe CommandServer.ready")
                val ready = runCatching { running.ready() }.getOrDefault(false)
                if (!ready) {
                    CrashTrail.mark("L7 skipped: command server not ready")
                    return@withContext Result.failure(IllegalStateException("command server not ready"))
                }
                CrashTrail.mark("before urlTestOutbound tag=$tag timeoutMs=$timeoutMs")
                Result.success(probeViaCommandClient(tag, url, timeoutMs))
            } catch (error: Throwable) {
                CrashTrail.recordFailure("L7 probe", error)
                Result.failure(error)
            }
        }

    fun notifyRevoked() {
        emitFailed(ConnectionCause.ServiceRevoked)
        abort()
    }

    private suspend fun startLocked(compiledJson: String, mode: RunMode) {
        val build = "versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE} " +
            "gitSha=${BuildConfig.GIT_SHA}"
        LerNetLog.i(TAG, "engine start $build")
        CrashTrail.mark("engine start $build")
        CrashTrail.mark("startLocked enter mode=$mode bytes=${compiledJson.length} stack=${ConfigAssembler.TUN_STACK}")
        setupIfNeeded()
        CrashTrail.mark("assembled-tun-keys=${AssembledKeys.summarize(compiledJson)}")
        CrashTrail.mark(
            "before checkConfig ${compiledJson.length} bytes mode=$mode stack=${ConfigAssembler.TUN_STACK} dns_mode=omit",
        )
        LerNetLog.i(TAG, "checkConfig ${compiledJson.length} bytes mode=$mode")
        LerNetLog.i(TAG, "assembled json: $compiledJson")
        nativeCall("checkConfig") { Libbox.checkConfig(compiledJson) }
        CrashTrail.mark("after checkConfig")
        closeStatusClient()
        closeServerLocked()
        CrashTrail.mark("before host.start $mode")
        LerNetLog.i(TAG, "starting host $mode")
        nativeCall("host.start") { host.start(mode) }
        CrashTrail.mark("after host.start")
        val platform = LibboxPlatformRegistry.await()
        CrashTrail.mark("platform ready ${platform.javaClass.simpleName}")
        CrashTrail.mark("before newCommandServer")
        val created = nativeCall("newCommandServer") {
            Libbox.newCommandServer(LibboxCommandHandler(::onServiceStopRequested), platform)
        }
        CrashTrail.mark("after newCommandServer")
        CrashTrail.mark("before CommandServer.start")
        nativeCall("CommandServer.start") { created.start() }
        CrashTrail.mark("after CommandServer.start")
        CrashTrail.mark("before startOrReloadService")
        nativeCall("startOrReloadService") { created.startOrReloadService(compiledJson, OverrideOptions()) }
        CrashTrail.mark("after startOrReloadService")
        server = created
        awaitCommandServerReady(created)
        LerNetLog.i(TAG, "command server started ready=${runCatching { created.ready() }.getOrDefault(false)}")
    }

    private fun <T> nativeCall(site: String, block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        CrashTrail.recordFailure(site, error)
        throw error
    }

    private fun attachStatusClient() {
        runCatching {
            val client = LibboxStatusClient { event ->
                val emitted = _events.tryEmit(event)
                if (!emitted) {
                    LerNetLog.w(TAG, "status event dropped")
                }
            }
            statusClient = client
            client.start()
        }.onFailure { error ->
            LerNetLog.w(TAG, "status client attach failed: ${error.message}", error)
        }
    }

    private fun setupIfNeeded() {
        if (setupDone) return
        val root = File(context.filesDir, "libbox")
        val working = File(root, "working")
        val temp = File(root, "temp")
        working.mkdirs()
        temp.mkdirs()
        CrashTrail.mark("native: await libboxReady (SFA path, no Seq.setContext)")
        try {
            LibboxNative.bootstrap(
                context,
                basePath = root.absolutePath,
                workingPath = working.absolutePath,
                tempPath = temp.absolutePath,
            )
            setupDone = LibboxNative.isReady
        } catch (error: Throwable) {
            CrashTrail.recordFailure("Libbox.setup", error)
            throw error
        }
    }

    private fun awaitCommandServerReady(created: CommandServer) {
        CrashTrail.mark("before CommandServer.ready poll")
        val deadlineNs = System.nanoTime() + READY_WAIT_NS
        while (System.nanoTime() < deadlineNs) {
            val ready = runCatching { created.ready() }.getOrDefault(false)
            if (ready) {
                CrashTrail.mark("command server ready")
                return
            }
            Thread.sleep(READY_POLL_MS)
        }
        error("command server not ready after ${READY_WAIT_NS / 1_000_000}ms")
    }

    private fun onServiceStopRequested() {
        CrashTrail.mark("before handler closeService")
        runCatching { server?.closeService() }
            .onFailure { LerNetLog.w(TAG, "handler closeService: ${it.message}", it) }
    }

    private fun closeStatusClient() {
        val running = statusClient
        statusClient = null
        running?.close()
    }

    private fun closeServerLocked() {
        val running = server
        server = null
        if (running == null) return
        CrashTrail.mark("before closeService")
        runCatching { running.closeService() }
            .onFailure { LerNetLog.w(TAG, "closeService: ${it.message}", it) }
        CrashTrail.mark("before CommandServer.close")
        runCatching { running.close() }
            .onFailure { LerNetLog.w(TAG, "command server close: ${it.message}", it) }
    }

    private fun emitStarted() {
        val emitted = _events.tryEmit(EngineEvent.Started)
        if (!emitted) {
            LerNetLog.w(TAG, "Started event dropped")
        }
    }

    private fun probeViaCommandClient(tag: String, url: String, timeoutMs: Int): Int {
        CrashTrail.mark("before newStandaloneCommandClient")
        val client = try {
            Libbox.newStandaloneCommandClient()
        } catch (error: Throwable) {
            CrashTrail.recordFailure("L7 newStandaloneCommandClient", error)
            throw error
        }
        CrashTrail.mark("after newStandaloneCommandClient")
        try {
            CrashTrail.mark("before L7 client.connect")
            try {
                client.connect()
            } catch (error: Throwable) {
                CrashTrail.recordFailure("L7 client.connect", error)
                throw error
            }
            CrashTrail.mark("after L7 client.connect")
            return urlTestOrGet(client, tag, url, timeoutMs)
        } finally {
            CrashTrail.mark("before L7 client.disconnect")
            runCatching { client.disconnect() }
                .onFailure { LerNetLog.w(TAG, "L7 probe client disconnect: ${it.message}", it) }
        }
    }

    private fun urlTestOrGet(client: CommandClient, tag: String, url: String, timeoutMs: Int): Int {
        CrashTrail.mark("before urlTestOutbound JNI tag=$tag")
        val tested = try {
            client.urlTestOutbound(tag, url, timeoutMs)
        } catch (error: Throwable) {
            CrashTrail.recordFailure("L7 urlTestOutbound", error)
            null
        }
        if (tested != null) {
            val failure = tested.error
            if (!failure.isNullOrBlank()) {
                error("urlTest $tag: $failure")
            }
            CrashTrail.mark("after urlTestOutbound JNI delay=${tested.delay}")
            return tested.delay
        }
        CrashTrail.mark("before getURLViaOutbound tag=$tag")
        val fetched = try {
            client.getURLViaOutbound(tag, url, timeoutMs, GET_URL_LIMIT, HTTPHeaders())
        } catch (error: Throwable) {
            CrashTrail.recordFailure("L7 getURLViaOutbound", error)
            throw error
        }
        if (fetched.status() !in 200..299) {
            error("getURL $tag HTTP ${fetched.status()}")
        }
        CrashTrail.mark("after getURLViaOutbound status=${fetched.status()}")
        return fetched.elapsedMs()
    }

    private fun emitFailed(cause: ConnectionCause) {
        val emitted = _events.tryEmit(EngineEvent.Failed(cause))
        if (!emitted) {
            LerNetLog.w(TAG, "Failed event dropped: ${cause.labelRu()}")
        }
    }

    companion object {
        private const val TAG = "LerNet.Libbox"
        private const val GRACEFUL_CLOSE_MS = 2_000L
        private const val GET_URL_LIMIT = 2_048
        private const val READY_WAIT_NS = 8_000_000_000L
        private const val READY_POLL_MS = 50L
    }
}
