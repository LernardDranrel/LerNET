package app.lernet.ui.diag

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lernet.LerNetApp
import app.lernet.R
import app.lernet.engine.ConnectionController
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.live.DestPing
import app.lernet.engine.live.LiveConn
import app.lernet.engine.log.JournalCeiling
import app.lernet.engine.redact.LerNetLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagUiState(
    val snapshot: ConnectionSnapshot = ConnectionSnapshot.idle(),
    val rows: List<LiveConn> = emptyList(),
    val recording: Boolean = false,
    val pingId: String? = null,
    val pingText: String? = null,
    val vpnUp: Boolean = false,
    val dnsOk: Boolean = false,
    val page: Int = 0,
    val pageCount: Int = 1,
    val expandedId: String? = null,
    val journalBytes: Long = 0,
    val journalCeiling: Int = JournalCeiling.bytes(JournalCeiling.DEFAULT_MB),
)

sealed class DiagIntent {
    data class SetRecording(val on: Boolean) : DiagIntent()

    data class Ping(val row: LiveConn) : DiagIntent()

    data object CopyLogs : DiagIntent()

    data object ShareLogs : DiagIntent()

    data object ExportRecording : DiagIntent()

    data object OlderPage : DiagIntent()

    data object NewerPage : DiagIntent()

    data class ToggleRow(val id: String) : DiagIntent()
}

sealed class DiagEvent {
    data class Copied(val text: String) : DiagEvent()

    data object ShareLogs : DiagEvent()

    data class ShareRecording(val text: String) : DiagEvent()

    data class Message(val text: String) : DiagEvent()
}

@HiltViewModel
class DiagViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    controller: ConnectionController,
) : ViewModel() {
    private val feed = controller.liveFeed
    private val pingId = MutableStateFlow<String?>(null)
    private val pingText = MutableStateFlow<String?>(null)
    private val page = MutableStateFlow(0)
    private val expandedId = MutableStateFlow<String?>(null)

    val events = MutableSharedFlow<DiagEvent>(extraBufferCapacity = 4)

    private val live = combine(
        controller.snapshot,
        feed.rows,
        feed.recording,
        pingId,
        pingText,
    ) { snapshot, rows, recording, id, ping ->
        LiveSlice(snapshot, rows, recording, id, ping)
    }

    val state: StateFlow<DiagUiState> = combine(live, page, expandedId) { slice, pageIndex, expanded ->
        val pages = ((slice.rows.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val safe = pageIndex.coerceIn(0, pages - 1)
        val store = (appContext as LerNetApp).logStore
        DiagUiState(
            snapshot = slice.snapshot,
            rows = newestPage(slice.rows, safe, PAGE_SIZE),
            recording = slice.recording,
            pingId = slice.pingId,
            pingText = slice.ping,
            vpnUp = slice.snapshot.state == ConnectionState.CONNECTED ||
                slice.snapshot.state == ConnectionState.CONNECTING ||
                slice.snapshot.state == ConnectionState.RECONNECTING,
            dnsOk = slice.snapshot.dnsOk,
            page = safe,
            pageCount = pages,
            expandedId = expanded,
            journalBytes = store.ringBytes(),
            journalCeiling = store.ringCeiling(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagUiState())

    fun onIntent(intent: DiagIntent) {
        when (intent) {
            is DiagIntent.SetRecording -> feed.setRecording(intent.on)
            is DiagIntent.Ping -> ping(intent.row)
            DiagIntent.CopyLogs -> copyLogs()
            DiagIntent.ShareLogs -> {
                events.tryEmit(DiagEvent.ShareLogs)
                Unit
            }
            DiagIntent.OlderPage -> page.update { (it + 1).coerceAtMost(10_000) }
            DiagIntent.NewerPage -> page.update { (it - 1).coerceAtLeast(0) }
            is DiagIntent.ToggleRow -> expandedId.update { current -> if (current == intent.id) null else intent.id }
            DiagIntent.ExportRecording -> exportRecording()
        }
    }

    private fun copyLogs() {
        viewModelScope.launch(Dispatchers.Default) {
            val live = feed.liveText()
            val recorded = feed.recordedText()
            val full = buildString {
                appendLine("=== LIVE ===")
                appendLine(live.ifBlank { "empty" })
                if (recorded.isNotBlank()) {
                    appendLine()
                    appendLine("=== RECORDING ===")
                    appendLine(recorded)
                }
                appendLine()
                appendLine("=== SESSION ===")
                append(LerNetLog.buffer.snapshot())
            }
            val clipped = if (full.length > COPY_MAX_CHARS) {
                "[Последние $COPY_MAX_CHARS символов; полный журнал — через «Поделиться».]\n" +
                    full.takeLast(COPY_MAX_CHARS)
            } else {
                full
            }
            events.emit(DiagEvent.Copied(clipped))
        }
    }

    private fun exportRecording() {
        viewModelScope.launch(Dispatchers.Default) {
            val text = feed.recordedText().ifBlank { feed.liveText() }
            if (text.isBlank()) {
                events.emit(DiagEvent.Message(if (state.value.recording) "idle" else "empty"))
            } else {
                events.emit(DiagEvent.ShareRecording(text))
            }
        }
    }

    private fun ping(row: LiveConn) {
        viewModelScope.launch {
            pingId.value = row.id
            pingText.value = "…"
            val parsed = DestPing.parseHostPort(row.dest)
            if (parsed == null) {
                pingText.value = appContext.getString(R.string.diag_ping_fail)
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                DestPing.tcp(
                    host = parsed.first,
                    port = parsed.second,
                    timeoutMs = 3_000,
                    nowMs = { System.currentTimeMillis() },
                    connect = { host, port, timeout ->
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(host, port), timeout)
                        }
                    },
                )
            }
            pingText.value = result.fold(
                onSuccess = { "$it мс" },
                onFailure = { appContext.getString(R.string.diag_ping_fail) },
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val COPY_MAX_CHARS = 250_000
    }
}

private data class LiveSlice(
    val snapshot: ConnectionSnapshot,
    val rows: List<LiveConn>,
    val recording: Boolean,
    val pingId: String?,
    val ping: String?,
)
