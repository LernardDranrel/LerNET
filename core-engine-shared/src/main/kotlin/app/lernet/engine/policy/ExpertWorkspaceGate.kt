package app.lernet.engine.policy

import kotlinx.coroutines.delay

/**
 * Waits until the Expert runtime exists, forcing one inventory refresh if it is still null.
 * Used by Windows (and any await before Edit/Save) so «Рабочая область ещё загружается» is not
 * thrown while the first collect is in flight, and a stuck load gets an honest timeout instead.
 */
object ExpertWorkspaceGate {
    const val DEFAULT_TIMEOUT_MS: Long = 30_000
    const val DEFAULT_STEP_MS: Long = 500

    /**
     * @return the ready value from [get], or null when [timeoutMs] elapsed without success
     *   (caller shows a timeout / [failed] message, never a perpetual «ещё загружается»).
     */
    suspend fun <T : Any> await(
        get: () -> T?,
        refreshOnce: suspend () -> Unit,
        failed: () -> Boolean = { false },
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        stepMs: Long = DEFAULT_STEP_MS,
    ): T? {
        get()?.let { return it }
        if (failed()) return null
        var refreshed = false
        var waited = 0L
        while (waited < timeoutMs) {
            get()?.let { return it }
            if (failed()) return null
            if (!refreshed) {
                refreshed = true
                runCatching { refreshOnce() }
                get()?.let { return it }
                if (failed()) return null
            }
            delay(stepMs)
            waited += stepMs
        }
        return get()
    }
}
