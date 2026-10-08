package app.lernet.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkLeverTravelTest {
    @Test fun `upward movement closes and downward movement opens the switch`() {
        assertEquals(1f, NetworkLeverTravel.move(0f, -44f, 44f), 0f)
        assertEquals(0f, NetworkLeverTravel.move(1f, 44f, 44f), 0f)
    }

    @Test fun `handle follows intermediate movement without snapping`() {
        assertEquals(.25f, NetworkLeverTravel.move(0f, -11f, 44f), 0f)
        assertEquals(.75f, NetworkLeverTravel.move(1f, 11f, 44f), 0f)
    }

    @Test fun `overdrag cannot pass either physical stop`() {
        assertEquals(1f, NetworkLeverTravel.move(.5f, -1000f, 44f), 0f)
        assertEquals(0f, NetworkLeverTravel.move(.5f, 1000f, 44f), 0f)
    }

    @Test fun `near centre release preserves acknowledged state`() {
        for (position in listOf(.41f, .5f, .59f)) {
            assertFalse(NetworkLeverTravel.target(position, false))
            assertTrue(NetworkLeverTravel.target(position, true))
        }
    }

    @Test fun `clear release selects the endpoint regardless of former state`() {
        for (checked in listOf(true, false)) {
            assertTrue(NetworkLeverTravel.target(.6f, checked))
            assertFalse(NetworkLeverTravel.target(.4f, checked))
        }
    }

    @Test fun `invalid deltas or travel size cannot corrupt preview position`() {
        for (delta in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(.5f, NetworkLeverTravel.move(.5f, delta, 44f), 0f)
        }
        for (travel in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(.5f, NetworkLeverTravel.move(.5f, 1f, travel), 0f)
        }
    }
}
