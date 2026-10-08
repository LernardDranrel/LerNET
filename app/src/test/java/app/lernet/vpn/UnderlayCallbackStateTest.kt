package app.lernet.vpn

import org.junit.Assert.*
import org.junit.Test

class UnderlayCallbackStateTest {
    @Test fun publishesOnlyWhenBothCallbackFactsArrive() {
        val state = UnderlayCallbackState<String, String, String>()
        state.available("wifi")
        assertNull(state.capabilities("wifi", "unmetered"))
        assertEquals("dns-original", state.link("wifi", "dns-original")?.link)
        assertEquals("dns-new", state.link("wifi", "dns-new")?.link)
    }

    @Test fun lateOldNetworkCallbacksCannotSelectItAgainOrRemoveNewNetwork() {
        val state = UnderlayCallbackState<String, String, String>()
        state.available("wifi")
        state.capabilities("wifi", "old")
        state.link("wifi", "old-dns")
        state.available("cell")
        assertNull(state.link("wifi", "stale-dns"))
        assertNull(state.capabilities("cell", "new"))
        assertEquals("cell", state.link("cell", "new-dns")?.network)
        assertFalse(state.lost("wifi"))
        assertEquals("new-dns", state.snapshot()?.link)
        assertTrue(state.lost("cell"))
        assertNull(state.snapshot())
    }

    @Test fun newRegistrationAndDuplicateAvailablePreserveConsistentFacts() {
        val state = UnderlayCallbackState<String, String, String>()
        state.available("wifi")
        state.capabilities("wifi", "caps")
        state.link("wifi", "dns")
        state.available("wifi")
        assertEquals("dns", state.snapshot()?.link)
        state.clear()
        assertNull(state.capabilities("wifi", "late"))
        assertNull(state.link("wifi", "late"))
    }
}
