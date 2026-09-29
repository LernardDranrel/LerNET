package app.lernet.ui.home

import app.lernet.config.model.Profile
import app.lernet.engine.RunMode
import app.lernet.settings.AppSettings

/** Resolve from persisted settings, not a screen flow that may still show the previous selection. */
internal data class HomeConnectionRequest(val profile: Profile, val settings: AppSettings) {
    val mode: RunMode get() = RunMode.resolve(profile.modeOverride, settings.mode)
}

internal fun homeConnectionRequest(
    settings: AppSettings,
    profiles: List<Profile>,
    requestedProfileId: String? = null,
): HomeConnectionRequest? {
    val profile = if (requestedProfileId != null) {
        profiles.firstOrNull { it.id == requestedProfileId }
    } else {
        profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.firstOrNull()
    }
    return profile?.let { HomeConnectionRequest(it, settings) }
}
