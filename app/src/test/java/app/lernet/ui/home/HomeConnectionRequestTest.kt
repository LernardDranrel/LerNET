package app.lernet.ui.home

import app.lernet.config.model.Profile
import app.lernet.config.model.ProfileSource
import app.lernet.engine.RunMode
import app.lernet.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class HomeConnectionRequestTest {
    private val old = Profile("old", "Old", 0, 0, ProfileSource.VLESS, "out", emptyList(), null, null)
    private val next = old.copy(id = "next", name = "Next", modeOverride = "PROXY")

    @Test fun persistedSelectionAndExplicitSwitchTakePrecedenceOverPreviousScreen() {
        val profiles = listOf(old, next)
        val fresh = AppSettings(activeProfileId = next.id, mode = RunMode.FULL_VPN)
        assertEquals(next, homeConnectionRequest(fresh, profiles)!!.profile)
        assertEquals(next, homeConnectionRequest(fresh.copy(activeProfileId = old.id), profiles, next.id)!!.profile)
        assertNull(homeConnectionRequest(fresh, profiles, "deleted"))
    }

    @Test fun importedProfileModeIsUsedByBothDisplayAndConnection() {
        val settings = AppSettings(mode = RunMode.FULL_VPN)
        val request = homeConnectionRequest(settings, listOf(next))!!
        assertEquals(RunMode.PROXY, request.mode)
        assertEquals(request.mode, HomeUiState(settings = settings, activeProfile = next).effectiveMode)
        assertEquals(RunMode.FULL_VPN, homeConnectionRequest(settings, listOf(old))!!.mode)
        assertEquals(RunMode.FULL_VPN, homeConnectionRequest(settings, listOf(old.copy(modeOverride = "unknown")))!!.mode)
    }
}
