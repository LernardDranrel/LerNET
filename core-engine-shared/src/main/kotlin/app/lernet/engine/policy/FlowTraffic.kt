package app.lernet.engine.policy

data class FlowCounters(
    val id: String,
    val startedAtMs: Long?,
    val observedAtMs: Long?,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
)

data class FlowTrafficRate(val upload: Long, val download: Long)

/** UI clocks do not create bandwidth evidence: rates require two confirmed counter snapshots. */
class FlowTrafficSampler {
    private val previous = mutableMapOf<String, FlowCounters>()
    private val rates = mutableMapOf<String, FlowTrafficRate>()

    fun update(flows: List<FlowCounters>): Map<String, FlowTrafficRate> {
        val ids = flows.map { it.id }.toSet()
        previous.keys.retainAll(ids)
        rates.keys.retainAll(ids)
        flows.forEach { point ->
            val stamp = point.observedAtMs ?: return@forEach
            val before = previous[point.id]
            if (before != null && before.observedAtMs == stamp) return@forEach
            previous[point.id] = point
            val elapsed = before?.observedAtMs?.let { stamp - it } ?: 0
            if (before == null || elapsed <= 0 || before.startedAtMs != point.startedAtMs ||
                point.uploadedBytes < before.uploadedBytes || point.downloadedBytes < before.downloadedBytes
            ) {
                rates.remove(point.id)
            } else {
                rates[point.id] = FlowTrafficRate(
                    ((point.uploadedBytes - before.uploadedBytes).toDouble() * 1000 / elapsed).toLong(),
                    ((point.downloadedBytes - before.downloadedBytes).toDouble() * 1000 / elapsed).toLong(),
                )
            }
        }
        return rates.toMap()
    }
}
