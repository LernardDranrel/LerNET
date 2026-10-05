package app.lernet.desktop.observation

import app.lernet.engine.net.observation.NetworkSnapshot
import app.lernet.engine.net.observation.NetworkObservationAnalysis
import app.lernet.engine.net.observation.ObservationSource

/** Passive snapshots and a separate event recording have independent capture times/lifetimes. */
internal data class ObservationSnapshotHistory(
    val current: NetworkSnapshot? = null,
    val previous: NetworkSnapshot? = null,
    val recording: ObservationSource? = null,
    val anchor: NetworkSnapshot? = null,
) {
    val report: NetworkSnapshot?
        get() = current?.let { snapshot ->
            recording?.let { snapshot.copy(sources = snapshot.sources.filterNot { it.id == "trace-events" } + it) } ?: snapshot
        }

    val comparisonBaseline: NetworkSnapshot? get() = anchor ?: previous

    fun pinBaseline() = copy(anchor = current)

    fun exportReport(log: List<String>): NetworkSnapshot? = report?.let { snapshot ->
        val baseline = comparisonBaseline?.copy(baseline = null, changes = emptyList(), diagnosticLog = emptyList(), diagnosticLogCapturedAt = null)
        snapshot.copy(baseline = baseline, changes = if (baseline != null) NetworkObservationAnalysis.compare(baseline, current ?: snapshot) else emptyList(),
            diagnosticLog = log.takeLast(400).map(app.lernet.desktop.ProbeDiagnostics::clean), diagnosticLogCapturedAt = System.currentTimeMillis())
    }

    fun refreshed(snapshot: NetworkSnapshot) = copy(previous = current, current = snapshot)

    fun recorded(source: ObservationSource): ObservationSnapshotHistory {
        require(source.id == "trace-events")
        return copy(recording = source)
    }

    fun clearRecording() = copy(recording = null)
}
