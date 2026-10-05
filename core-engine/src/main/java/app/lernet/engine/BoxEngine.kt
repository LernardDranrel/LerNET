package app.lernet.engine

import app.lernet.engine.live.LiveConn
import app.lernet.engine.policy.PolicyControlCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class EngineEvent {
    data object Started : EngineEvent()

    data class Failed(val cause: ConnectionCause) : EngineEvent()

    data class LogLine(val line: String) : EngineEvent()

    data class Status(
        val uplinkBps: Long,
        val downlinkBps: Long,
        val uplinkTotal: Long,
        val downlinkTotal: Long,
        val connectionsOut: Int,
    ) : EngineEvent()

    /** DoH/DNS exchange finished with answers. Status counters often omit hijack bytes. */
    data class DnsAlive(val answers: Int) : EngineEvent()

    data class Connections(
        val reset: Boolean,
        val rows: List<LiveConn>,
    ) : EngineEvent()
}

interface BoxEngine {
    val policyControlCapabilities: PolicyControlCapabilities
        get() = PolicyControlCapabilities.RESTART_ONLY

    val isNativeAvailable: Boolean

    val engineVersion: String

    val events: Flow<EngineEvent>

    suspend fun start(compiledJson: String, mode: RunMode)

    suspend fun probeOutbound(tag: String, url: String, timeoutMs: Int): Result<Int> =
        Result.success(0)

    suspend fun stop()

    fun abort()
}

class UnavailableBoxEngine : BoxEngine {
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)

    override val isNativeAvailable: Boolean = false

    override val engineVersion: String = "unavailable"

    override val events: Flow<EngineEvent> = _events.asSharedFlow()

    override suspend fun start(compiledJson: String, mode: RunMode) {
        if (compiledJson.isBlank()) {
            _events.emit(EngineEvent.Failed(ConnectionCause.InvalidConfig(listOf("пустой compiled JSON"))))
            return
        }
        _events.emit(EngineEvent.Failed(ConnectionCause.EngineUnavailable))
    }

    override suspend fun probeOutbound(tag: String, url: String, timeoutMs: Int): Result<Int> =
        Result.failure(IllegalStateException("ядро не подключено"))

    override suspend fun stop() = Unit

    override fun abort() = Unit
}
