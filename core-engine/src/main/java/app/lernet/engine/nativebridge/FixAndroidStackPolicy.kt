package app.lernet.engine.nativebridge

/**
 * LxBox / SFA `Bugs.fixAndroidStack` (Go issue 68760).
 * True for API 24–25 (N / N_MR1), API ≥ 28 (P), and debug builds.
 */
object FixAndroidStackPolicy {
    const val API_N = 24
    const val API_N_MR1 = 25
    const val API_P = 28

    fun enabled(sdkInt: Int, debug: Boolean): Boolean =
        debug ||
            sdkInt in API_N..API_N_MR1 ||
            sdkInt >= API_P
}
