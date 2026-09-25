package app.lernet.vpn

import app.lernet.config.redact.SecretRedactor
import app.lernet.engine.EngineEvent
import app.lernet.engine.compile.DnsQueryLog
import app.lernet.engine.compile.DnsQueryRecord
import app.lernet.engine.live.LiveConn
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.redact.LerNetLog
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.DnsQuery
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator

internal class LibboxStatusClient(
    private val emit: (EngineEvent) -> Unit,
) : CommandClientHandler {
    private var client: CommandClient? = null

    fun start() {
        close()
        if (!startLocked(withConnections = true)) {
            LerNetLog.w(TAG, "status client with CommandConnections failed — retry without it")
            close()
            startLocked(withConnections = false)
        }
    }

    private fun startLocked(withConnections: Boolean): Boolean =
        runCatching {
            val options = CommandClientOptions()
            options.addCommand(Libbox.CommandStatus)
            options.addCommand(Libbox.CommandLog)
            options.addCommand(Libbox.CommandDNS)
            if (withConnections) {
                options.addCommand(Libbox.CommandConnections)
            }
            options.dnsIncludeAnswers = true
            options.statusInterval = STATUS_INTERVAL_NS
            CrashTrail.mark("before newCommandClient connections=$withConnections")
            val next = Libbox.newCommandClient(this, options)
            client = next
            CrashTrail.mark("before status client.connect")
            next.connect()
        }.onFailure { error ->
            LerNetLog.w(TAG, "status client start failed: ${error.message}", error)
            close()
        }.isSuccess

    fun close() {
        val running = client
        client = null
        if (running == null) return
        CrashTrail.mark("before status client.disconnect")
        runCatching { running.disconnect() }
            .onFailure { LerNetLog.w(TAG, "status client disconnect: ${it.message}", it) }
    }

    override fun writeStatus(message: StatusMessage?) {
        runCatching {
            if (message == null) return
            emit(
                EngineEvent.Status(
                    uplinkBps = message.uplink,
                    downlinkBps = message.downlink,
                    uplinkTotal = message.uplinkTotal,
                    downlinkTotal = message.downlinkTotal,
                    connectionsOut = message.connectionsOut,
                ),
            )
        }.onFailure { LerNetLog.w(TAG, "writeStatus failed: ${it.message}", it) }
    }

    override fun connected() {
        runCatching { LerNetLog.i(TAG, "status client connected") }
            .onFailure { LerNetLog.w(TAG, "connected callback failed: ${it.message}", it) }
    }

    override fun disconnected(message: String?) {
        runCatching {
            if (!message.isNullOrBlank()) {
                LerNetLog.w(TAG, "status client disconnected: $message")
            }
        }.onFailure { LerNetLog.w(TAG, "disconnected callback failed: ${it.message}", it) }
    }

    override fun clearLogs() = Unit

    override fun initializeClashMode(modes: StringIterator?, current: String?) = Unit

    override fun setDefaultLogLevel(level: Int) = Unit

    override fun updateClashMode(mode: String?) = Unit

    override fun writeConnectionEvents(events: ConnectionEvents?) {
        if (events == null) return
        runCatching {
            val iterator = events.iterator() ?: return
            val rows = mutableListOf<LiveConn>()
            while (iterator.hasNext()) {
                val event = iterator.next()
                val connection = event.connection ?: continue
                rows += LiveConnMapper.from(connection)
            }
            if (rows.isEmpty() && !events.reset) return
            emit(EngineEvent.Connections(reset = events.reset, rows = rows))
        }.onFailure { LerNetLog.w(TAG, "writeConnectionEvents failed: ${it.message}", it) }
    }

    override fun writeDNSQuery(query: DnsQuery?) {
        if (query == null) return
        runCatching {
            val answers = answerCount(query)
            val line = DnsQueryLog.format(
                DnsQueryRecord(
                    domain = query.domain.orEmpty(),
                    queryType = query.queryType,
                    failed = query.failed,
                    error = query.error,
                    rcode = query.rcode,
                    answerCount = answers,
                    serverType = query.dnsServerType,
                    server = query.dnsServer,
                ),
            )
            LerNetLog.i(TAG, line)
            emit(EngineEvent.LogLine(line))
            if (!query.failed && query.error.isNullOrBlank()) {
                emit(EngineEvent.DnsAlive(answers))
            }
        }.onFailure { LerNetLog.w(TAG, "writeDNSQuery failed: ${it.message}", it) }
    }

    override fun writeGroups(groups: OutboundGroupIterator?) = Unit

    override fun writeLogs(logs: LogIterator?) {
        if (logs == null) return
        runCatching {
            while (logs.hasNext()) {
                val entry = logs.next()
                val message = entry.message.orEmpty()
                if (message.isBlank()) continue
                val safe = SecretRedactor.redact(message)
                LerNetLog.i(TAG, safe)
                emit(EngineEvent.LogLine(safe))
            }
        }.onFailure { LerNetLog.w(TAG, "writeLogs failed: ${it.message}", it) }
    }

    override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) = Unit

    private fun answerCount(query: DnsQuery): Int {
        val answers = query.answers() ?: return 0
        var count = 0
        while (answers.hasNext()) {
            answers.next()
            count += 1
        }
        return count
    }

    companion object {
        private const val TAG = "LerNet.Libbox"
        private const val STATUS_INTERVAL_NS = 1_000_000_000L
    }
}
