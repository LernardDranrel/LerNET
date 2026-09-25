package app.lernet.engine

fun interface EpochClock {
    fun nowMs(): Long
}

class SystemEpochClock : EpochClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

class FakeClock(initialMs: Long = 0L) : EpochClock {
    var nowMs: Long = initialMs

    override fun nowMs(): Long = nowMs

    fun advance(deltaMs: Long) {
        nowMs += deltaMs
    }
}
