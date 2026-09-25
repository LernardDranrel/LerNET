package app.lernet.engine.log

/**
 * Warmup (`Libbox.version` / setup) must not overwrite the death last-crumb
 * after an unclean boot. Ring still records every line. Last-crumb file
 * unfreezes on the next user Connect tap or a clean stop marker.
 */
object LastCrumbPolicy {
    const val CONNECT_TAP = "ui connect tap"
    const val CLEAN_MARKER = "session marker clean"

    fun overwriteLastFile(frozen: Boolean, step: String): Boolean =
        !frozen || releasesFreeze(step)

    fun nextFrozen(frozen: Boolean, step: String): Boolean =
        if (releasesFreeze(step)) false else frozen

    fun releasesFreeze(step: String): Boolean =
        step.startsWith(CONNECT_TAP) || step == CLEAN_MARKER
}
