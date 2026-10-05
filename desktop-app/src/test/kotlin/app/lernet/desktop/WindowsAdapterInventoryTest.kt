package app.lernet.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsAdapterInventoryTest {
    private val guid = "134ab342-a65b-41a3-b2a4-daf023fcd995"

    @Test
    fun ownedTunCannotBeChosenWhileUnrelatedAdapterKeepsItsIdentity() {
        val raw = """[{"guid":"{$guid}","name":"Company VPN","index":17,"status":"Up","description":"Adapter"}]"""
        val owned = WindowsAdapterInventory.parse(raw, 17).single()
        assertFalse(owned.eligible)
        assertTrue(owned.reason.orEmpty().contains("TUN"))
        val available = WindowsAdapterInventory.parse(raw, 18).single()
        assertTrue(available.eligible)
        assertEquals(guid, available.binding.guid)
        assertEquals(17, available.binding.index)
    }

    @Test
    fun disconnectedAdapterRemainsVisibleWithAnExplicitReason() {
        val rows = WindowsAdapterInventory.parse(
            """[{"guid":"$guid","name":"Company VPN","index":17,"status":"Disconnected"}]""", null,
        )
        assertFalse(rows.single().eligible)
        assertTrue(rows.single().reason.orEmpty().contains("отключён"))
    }

    @Test
    fun malformedIdentityIsNeverTurnedIntoInterfaceEvidence() {
        assertTrue(
            WindowsAdapterInventory.parse(
                """[{"guid":"not-a-guid","name":"Company VPN","index":17,"status":"Up"},
                {"guid":"$guid","name":"Company VPN","index":"17","status":"Up"}]""",
                null,
            ).isEmpty()
        )
    }
}
