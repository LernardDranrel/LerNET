package app.lernet.desktop

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Test

/** Barriers exercise the actual coordinator gate without starting any process or modifying the network. */
class DesktopExpertStartGateTest {
    @Test
    fun stopDiscardsASecondStartQueuedBehindAnActiveStart(): Unit = runBlocking {
        val gate = DesktopExpertStartGate(Mutex())
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var starts = 0
        val firstTicket = gate.requestStart()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.runIfCurrent(firstTicket) {
                starts++
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val secondTicket = gate.requestStart()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.runIfCurrent(secondTicket) { starts++ }
        }
        assertThat(second.isCompleted).isFalse()

        gate.requestStop()
        releaseFirst.complete(Unit)
        first.join()
        second.join()

        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun aLaterIntentionalStartIsAllowedAfterStop(): Unit = runBlocking {
        val mutex = Mutex(locked = true)
        val gate = DesktopExpertStartGate(mutex)
        val started = mutableListOf<String>()
        val oldTicket = gate.requestStart()
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.runIfCurrent(oldTicket) { started += "old" }
        }
        gate.requestStop()
        val newTicket = gate.requestStart()
        val current = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.runIfCurrent(newTicket) { started += "current" }
        }

        mutex.unlock()
        old.join()
        current.join()

        assertThat(started).containsExactly("current")
    }

    @Test
    fun stopInvalidatesStartAcceptedBeforeItsCoroutineWasScheduled(): Unit = runBlocking {
        val gate = DesktopExpertStartGate(Mutex())
        val ticket = gate.requestStart()
        var started = false

        gate.requestStop()
        gate.runIfCurrent(ticket) { started = true }

        assertThat(started).isFalse()
    }

    @Test
    fun stopDuringHandoverPreparationInvalidatesThePendingStart(): Unit = runBlocking {
        val gate = DesktopExpertStartGate(Mutex())
        val prepared = CompletableDeferred<Unit>()
        val continueStart = CompletableDeferred<Unit>()
        val ticket = gate.requestStart()
        var started = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.runIfCurrent(ticket) {
                prepared.complete(Unit)
                continueStart.await()
                if (gate.isCurrent(ticket)) started = true
            }
        }
        prepared.await()
        gate.requestStop()
        continueStart.complete(Unit)
        job.join()
        assertThat(started).isFalse()
    }

    @Test
    fun failedShutdownStillCancelsCoordinatorScopeAndWaitingJobs(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val waiting = CompletableDeferred<Unit>()
        val child = scope.launch(start = CoroutineStart.UNDISPATCHED) { waiting.await() }
        val failure = IllegalStateException("cleanup failed")
        val actual = runCatching {
            cancelExpertScopeAfterShutdown(scope) { throw failure }
        }.exceptionOrNull()
        child.join()

        assertThat(actual).isSameInstanceAs(failure)
        assertThat(scope.isActive).isFalse()
        assertThat(child.isCancelled).isTrue()
    }
}
