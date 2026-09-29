package app.lernet.desktop.observation

import app.lernet.engine.net.observation.*
import org.junit.Assert.*
import org.junit.Test

class ObservationSnapshotHistoryTest {
    private fun snapshot(time: Long) = NetworkSnapshot(id = "$time", platform = "Windows", startedAt = time)
    private fun recording(time: Long) = ObservationSource("trace-events", "Запись", "События", capturedAt = time)

    @Test fun refreshDoesNotDiscardSeparateRecordingOrCompareItAsSettings() {
        val first = snapshot(1)
        val next = snapshot(2)
        val events = recording(3)
        val history = ObservationSnapshotHistory().refreshed(first).recorded(events).refreshed(next)
        assertEquals(first, history.previous)
        assertEquals(next, history.current)
        assertEquals(listOf(events), history.report!!.sources)
        assertTrue(history.current!!.sources.isEmpty())
    }

    @Test fun recordFinishingBeforeFirstSnapshotIsRetained() {
        val events = recording(1)
        val history = ObservationSnapshotHistory().recorded(events)
        assertNull(history.report)
        assertEquals(listOf(events), history.refreshed(snapshot(2)).report!!.sources)
    }

    @Test fun newRecordingRemovesPreviousResultsWithoutChangingPassiveBaseline() {
        val first = snapshot(1)
        val history = ObservationSnapshotHistory().refreshed(first).recorded(recording(2)).clearRecording()
        assertEquals(first, history.current)
        assertEquals(first, history.report)
        assertNull(history.recording)
    }
}
