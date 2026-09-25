package app.lernet.engine.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionHealthTest {
    @Test
    fun runningMarkerIsUnclean() {
        assertThat(SessionHealth.previousUnclean(SessionHealth.MARKER_RUNNING)).isTrue()
        assertThat(SessionHealth.previousUnclean("running\n")).isTrue()
        assertThat(SessionHealth.previousUnclean(SessionHealth.MARKER_CLEAN)).isFalse()
        assertThat(SessionHealth.previousUnclean(null)).isFalse()
    }

    @Test
    fun runningWithoutCrashLastOffers() {
        val offer = SessionHealth.shouldOffer(
            previousMarker = SessionHealth.MARKER_RUNNING,
            pendingExists = false,
            ringBytes = 0L,
            crashLastModifiedMs = null,
            seenCrashModifiedMs = null,
        )
        assertThat(offer).isTrue()
        val uiOfferLastLogs = offer
        assertThat(uiOfferLastLogs).isTrue()
    }

    @Test
    fun leftoverRingWithoutCleanMarkerOffers() {
        assertThat(
            SessionHealth.shouldOffer(
                previousMarker = null,
                pendingExists = false,
                ringBytes = 128L,
                crashLastModifiedMs = null,
                seenCrashModifiedMs = null,
            ),
        ).isTrue()
    }

    @Test
    fun cleanStopWithRingDoesNotOffer() {
        assertThat(
            SessionHealth.shouldOffer(
                previousMarker = SessionHealth.MARKER_CLEAN,
                pendingExists = false,
                ringBytes = 4096L,
                crashLastModifiedMs = null,
                seenCrashModifiedMs = null,
            ),
        ).isFalse()
    }

    @Test
    fun pendingOrUnseenCrashLastOffers() {
        assertThat(
            SessionHealth.shouldOffer(
                previousMarker = SessionHealth.MARKER_CLEAN,
                pendingExists = true,
                ringBytes = 0L,
            ),
        ).isTrue()
        assertThat(
            SessionHealth.shouldOffer(
                previousMarker = SessionHealth.MARKER_CLEAN,
                pendingExists = false,
                ringBytes = 0L,
                crashLastModifiedMs = 10L,
                seenCrashModifiedMs = null,
            ),
        ).isTrue()
        assertThat(
            SessionHealth.shouldOffer(
                previousMarker = SessionHealth.MARKER_CLEAN,
                pendingExists = false,
                ringBytes = 0L,
                crashLastModifiedMs = 10L,
                seenCrashModifiedMs = 10L,
            ),
        ).isFalse()
    }

    @Test
    fun acknowledgedUncleanDoesNotOfferAgain() {
        assertThat(
            SessionHealth.shouldOffer(
                previousMarker = SessionHealth.MARKER_RUNNING,
                pendingExists = true,
                ringBytes = 4096L,
                crashLastModifiedMs = 10L,
                seenCrashModifiedMs = 10L,
                acknowledged = true,
            ),
        ).isFalse()
    }

    @Test
    fun acknowledgedUncleanStillOffersWhenCrashLastIsNew() {
        assertThat(
            SessionHealth.shouldOffer(
                previousMarker = SessionHealth.MARKER_RUNNING,
                pendingExists = false,
                ringBytes = 0L,
                crashLastModifiedMs = 11L,
                seenCrashModifiedMs = 10L,
                acknowledged = true,
            ),
        ).isTrue()
    }
}
