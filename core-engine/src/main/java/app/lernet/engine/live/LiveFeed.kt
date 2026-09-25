package app.lernet.engine.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LiveFeed(
    private val maxRows: Int = DEFAULT_MAX_ROWS,
) {
    private val recorder = LiveRecorder()
    private val _rows = MutableStateFlow<List<LiveConn>>(emptyList())
    private val _recording = MutableStateFlow(false)

    val rows: StateFlow<List<LiveConn>> = _rows.asStateFlow()
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun apply(reset: Boolean, incoming: List<LiveConn>) {
        val next = if (reset) incoming else merge(_rows.value, incoming)
        val chronological = if (next.isNotEmpty() && next.all { it.createdAt > 0L }) {
            next.sortedBy { it.createdAt }
        } else {
            next
        }
        _rows.value = chronological.takeLast(maxRows)
        recorder.apply(reset, incoming)
    }

    fun setRecording(on: Boolean) {
        recorder.setRecording(on)
        _recording.value = on
    }

    fun recordedText(): String = recorder.exportText()

    fun liveText(): String = _rows.value.joinToString(separator = "\n", transform = LiveConnFormat::line)

    private fun merge(current: List<LiveConn>, incoming: List<LiveConn>): List<LiveConn> {
        if (incoming.isEmpty()) return current
        val byId = current.associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    companion object {
        const val DEFAULT_MAX_ROWS = 80
    }
}
