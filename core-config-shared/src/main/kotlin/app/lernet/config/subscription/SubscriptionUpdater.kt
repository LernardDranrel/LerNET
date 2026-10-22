package app.lernet.config.subscription

import app.lernet.config.parse.ImportCoordinator
import app.lernet.config.parse.ImportReport
import app.lernet.config.parse.ImportResult
import kotlin.random.Random

/** Fetch, read and plan one subscription refresh; the platform applies the plan to its own storage. */
class SubscriptionUpdater(
    private val coordinator: ImportCoordinator,
    private val book: SubscriptionBook,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    sealed interface Prepared {
        data class Ready(val url: String, val plan: RefreshPlan, val fetched: ImportResult.Success) : Prepared {
            /** One line for the screen; [skipped] mentions lines that were not added. */
            val message: String get() = plan.summary(ImportReport.skipped(fetched.skipped))
        }
        data class Failed(val url: String, val message: String) : Prepared
    }

    /** Subscriptions that are due now, from the addresses the configs carry. */
    fun due(urls: Set<String>): List<String> {
        val at = now()
        val known = book.read().associateBy { it.url }
        return urls.filter { url ->
            val entry = known[url] ?: return@filter false.also {
                // A subscription imported before refreshes existed: start its schedule from now, do not fetch at once.
                book.update(url) { e -> e.copy(lastSuccessMs = at, jitterMs = SubscriptionSchedule.jitter(random)) }
            }
            SubscriptionSchedule.isDue(entry, at)
        }
    }

    fun prepare(url: String, existing: List<SubscribedConfig>): Prepared {
        val result = coordinator.importSubscription(url)
        return when (result) {
            is ImportResult.Failure -> {
                val message = "Подписку не удалось обновить: " + result.errors.joinToString("; ") { it.message }
                book.update(url) { it.copy(lastAttemptMs = now(), failedSinceSuccess = true, lastMessage = message, jitterMs = SubscriptionSchedule.jitter(random)) }
                Prepared.Failed(url, message)
            }
            is ImportResult.Success -> {
                val origins = book.entry(url)?.origins.orEmpty()
                Prepared.Ready(url, SubscriptionRefresh.plan(existing, result.drafts, origins), result)
            }
        }
    }

    /** After the platform saved [ready]; [addedIds] maps every added draft (by position in plan.adds) to its new config id. */
    fun applied(ready: Prepared.Ready, addedIds: List<String>) {
        val origins = ready.plan.origins.toMutableMap()
        ready.plan.adds.zip(addedIds).forEach { (draft, id) -> origins[id] = SubscriptionRefresh.originOf(draft) }
        book.update(ready.url) {
            it.copy(
                intervalHours = ready.fetched.updateIntervalHours ?: it.intervalHours,
                lastSuccessMs = now(),
                lastAttemptMs = now(),
                failedSinceSuccess = false,
                jitterMs = SubscriptionSchedule.jitter(random),
                lastMessage = ready.message,
                origins = origins,
            )
        }
    }

    /** First import of a subscription: remember its interval and the origin of each new config. */
    fun imported(url: String, result: ImportResult.Success, ids: List<String>) {
        book.update(url) {
            it.copy(
                intervalHours = result.updateIntervalHours ?: it.intervalHours,
                lastSuccessMs = now(),
                lastAttemptMs = now(),
                failedSinceSuccess = false,
                jitterMs = SubscriptionSchedule.jitter(random),
                origins = it.origins + result.drafts.zip(ids).associate { (draft, id) -> id to SubscriptionRefresh.originOf(draft) },
            )
        }
    }

    fun failedToSave(url: String, message: String) {
        book.update(url) { it.copy(lastAttemptMs = now(), failedSinceSuccess = true, lastMessage = message) }
    }
}
