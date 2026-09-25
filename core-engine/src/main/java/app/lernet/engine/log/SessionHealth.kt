package app.lernet.engine.log

object SessionHealth {
    const val MARKER_RUNNING = "running"
    const val MARKER_CLEAN = "clean"

    fun previousUnclean(previousMarker: String?): Boolean =
        previousMarker?.trim() == MARKER_RUNNING

    /**
     * Native SIGSEGV never writes crash-last. Offer when the previous process
     * did not mark clean, a pending flag remains, a leftover ring exists
     * without a clean stop, or crash-last is unseen.
     *
     * [acknowledged] is the persisted Close/Share of that unclean episode.
     * A newer crash-last still offers. A later connect clears the ack so the
     * next death is a new episode.
     */
    fun shouldOffer(
        previousMarker: String?,
        pendingExists: Boolean,
        ringBytes: Long,
        crashLastModifiedMs: Long? = null,
        seenCrashModifiedMs: Long? = null,
        acknowledged: Boolean = false,
    ): Boolean {
        val crashUnseen = crashLastModifiedMs != null && crashLastModifiedMs != seenCrashModifiedMs
        if (crashUnseen) return true
        val staleUnclean = previousUnclean(previousMarker) || pendingExists
        if (acknowledged && staleUnclean) return false
        if (staleUnclean) return true
        return ringBytes > 0L && previousMarker.isNullOrBlank()
    }
}
