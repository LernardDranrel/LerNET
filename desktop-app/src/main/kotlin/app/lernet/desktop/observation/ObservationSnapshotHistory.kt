package app.lernet.desktop.observation

import app.lernet.engine.net.observation.NetworkSnapshot
import app.lernet.engine.net.observation.ObservationSource

/** Passive snapshots and a separate event recording have independent capture times/lifetimes. */
internal data class ObservationSnapshotHistory(
    val current: NetworkSnapshot? = null,
    val previous: NetworkSnapshot? = null,
    val recording: ObservationSource? = null,
) {
    val report: NetworkSnapshot?
        get() = current?.let { snapshot ->
            recording?.let { snapshot.copy(sources = snapshot.sources.filterNot { it.id == "trace-events" } + it) } ?: snapshot
        }

    fun refreshed(snapshot: NetworkSnapshot) = copy(previous = current, current = snapshot)

    fun recorded(source: ObservationSource): ObservationSnapshotHistory {
        require(source.id == "trace-events")
        return copy(recording = source)
    }

    fun clearRecording() = copy(recording = null)
}
