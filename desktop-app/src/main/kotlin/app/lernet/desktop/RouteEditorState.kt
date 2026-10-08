package app.lernet.desktop

import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** UI-only state is restored independently from engine state and never applies a policy. */
internal inline fun <reified T> routeEditorStateSaver(): Saver<T, String> = Saver(
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString<T>(it) },
)
