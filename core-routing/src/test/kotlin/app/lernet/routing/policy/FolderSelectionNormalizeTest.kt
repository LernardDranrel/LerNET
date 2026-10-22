package app.lernet.routing.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSelectionNormalizeTest {
    @Test
    fun `legacy fastest becomes stable`() {
        @Suppress("DEPRECATION")
        assertEquals(FolderSelection.STABLE, FolderSelection.LOWEST_LATENCY.normalized())
        assertEquals(FolderSelection.PREFERRED, FolderSelection.PREFERRED.normalized())
        assertEquals(FolderSelection.STABLE, FolderSelection.STABLE.normalized())
        @Suppress("DEPRECATION")
        val policy = FolderPolicy("f", selection = FolderSelection.LOWEST_LATENCY)
        assertEquals(FolderSelection.STABLE, policy.normalized().selection)
        assertEquals(
            FolderSelection.STABLE,
            NetworkPolicy(folderPolicies = listOf(policy)).normalizedFolderSelections()
                .folderPolicies.single().selection,
        )
    }
}
