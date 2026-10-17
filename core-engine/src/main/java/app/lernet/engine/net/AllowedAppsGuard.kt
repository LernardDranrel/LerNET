package app.lernet.engine.net

/**
 * Android `VpnService.Builder.addAllowedApplication` turns the VPN into an allow-list.
 * If every requested package fails (uninstalled, renamed), the builder has no allow-list left
 * and Android would route EVERY app through the tunnel — the opposite of what the user chose.
 *
 * Safe choice: refuse to establish the interface with a clear, non-retryable error. Apps outside
 * the selection were never meant to be in the VPN, so stopping changes nothing for them, while
 * silently capturing everything would.
 */
object AllowedAppsGuard {
    /** Stable marker that survives libbox error wrapping; [app.lernet.engine.EngineErrorMapper] keys on it. */
    const val MARKER = "lernet: none of the selected apps could be added"

    const val USER_MESSAGE =
        "Ни одно из выбранных для VPN приложений не найдено на телефоне. VPN не включён, " +
            "чтобы через него не пошли все приложения. Выберите приложения заново."

    /** Throws a checked [Exception] (gomobile-safe, like [VpnGuard]) when an allow-list was requested but nothing from it was accepted by Android. */
    fun requireSomeAllowed(requested: Collection<String>, added: Collection<String>) {
        if (requested.isNotEmpty() && added.isEmpty()) {
            throw Exception("$MARKER (requested ${requested.size})")
        }
    }
}
