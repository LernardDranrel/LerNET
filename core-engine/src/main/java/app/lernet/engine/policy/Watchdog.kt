package app.lernet.engine.policy

import app.lernet.engine.EpochClock

class Watchdog(
    private val clock: EpochClock,
    private val timeoutMs: Long,
) {
    private var lastOkMs: Long = clock.nowMs()

    fun markSuccess() {
        lastOkMs = clock.nowMs()
    }

    fun isExpired(): Boolean = clock.nowMs() - lastOkMs >= timeoutMs

    fun silentForMs(): Long = clock.nowMs() - lastOkMs
}
