package app.lernet.subscription

import android.content.Context
import app.lernet.config.parse.ImportCoordinator
import app.lernet.config.parse.ImportResult
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.subscription.SubscriptionBook
import app.lernet.config.subscription.SubscriptionRefresh
import app.lernet.config.subscription.SubscriptionEntry
import app.lernet.config.subscription.SubscriptionSchedule
import app.lernet.config.model.DnsPolicy
import app.lernet.config.subscription.SubscriptionUpdater
import app.lernet.engine.redact.LerNetLog
import app.lernet.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Refreshes subscriptions by button and on their schedule (header Profile-Update-Interval or 12 h, with a random shift).
 * Requests go through the VPN when it is on: [ImportCoordinator] fetches through AppTraffic.
 * A refreshed config takes effect at the next connect; the running VPN is never restarted from here.
 */
@Singleton
class SubscriptionAutoRefresh internal constructor(
    private val coordinator: ImportCoordinator,
    private val repository: ConfigRepository,
    private val settings: SettingsStore,
    bookFile: File,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        coordinator: ImportCoordinator,
        repository: ConfigRepository,
        settings: SettingsStore,
    ) : this(coordinator, repository, settings, File(context.noBackupFilesDir, "subscriptions.json"))

    // The address of a subscription is a secret: the book lives outside backups.
    private val book = SubscriptionBook(bookFile.toPath())
    private val updater = SubscriptionUpdater(coordinator, book)
    private val lock = Mutex()
    private var loop: Job? = null

    fun start(scope: CoroutineScope) {
        if (loop != null) return
        loop = scope.launch(Dispatchers.IO) {
            runCatching { repository.adoptAllSubscriptions() }.onFailure {
                if (it is CancellationException) throw it
                LerNetLog.w(TAG, "subscription folder adoption failed: ${it.message}")
            }
            while (isActive) {
                runCatching { refreshDue() }.onFailure {
                    if (it is CancellationException) throw it
                    LerNetLog.w(TAG, "scheduled refresh failed: ${it.message}")
                }
                delay(SubscriptionSchedule.TICK_MS)
            }
        }
    }

    suspend fun refreshDue() {
        val urls = repository.subscriptionUrls()
        book.retain(urls)
        updater.due(urls).forEach { refresh(it) }
    }

    /** Refresh one subscription now; returns the line to show to the user. */
    suspend fun refresh(url: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val active = settings.settings.first().activeProfileId
            when (val prepared = updater.prepare(url, repository.subscribedConfigs(url))) {
                is SubscriptionUpdater.Prepared.Failed -> prepared.message.also { LerNetLog.w(TAG, "refresh failed: $it") }
                is SubscriptionUpdater.Prepared.Ready -> try {
                    val policy = settings.settings.first().defaultDnsPolicy
                    val added = repository.applySubscriptionPlan(url, prepared.plan, policy)
                    updater.applied(prepared, added)
                    if (active != null && repository.getProfile(active) == null) settings.setActiveProfile(null)
                    prepared.message
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = "Подписку не удалось сохранить: ${error.message ?: error.javaClass.simpleName}"
                    updater.failedToSave(url, message)
                    message
                }
            }
        }
    }

    /**
     * First import creates the subscription folder. Importing the same address again refreshes that folder
     * instead of dumping another copy into the user's configs.
     */
    suspend fun install(result: ImportResult.Success, policy: DnsPolicy): List<String> = withContext(Dispatchers.IO) {
        val url = result.drafts.firstOrNull()?.subscriptionUrl?.takeIf(String::isNotBlank) ?: return@withContext emptyList()
        lock.withLock {
            val existing = repository.subscribedConfigs(url)
            if (existing.isEmpty()) {
                val ids = repository.importDrafts(result.drafts, policy)
                repository.adoptSubscription(url)
                runCatching { updater.imported(url, result, ids) }
                    .onFailure { LerNetLog.w(TAG, "subscription book write failed: ${it.message}") }
                ids
            } else {
                val origins = book.entry(url)?.origins.orEmpty()
                val plan = SubscriptionRefresh.plan(existing, result.drafts, origins)
                val added = repository.applySubscriptionPlan(url, plan, policy)
                updater.applied(SubscriptionUpdater.Prepared.Ready(url, plan, result), added)
                val removed = plan.removes.toSet()
                existing.map { it.id }.filter { it !in removed } + added
            }
        }
    }

    /** Drop the book entry after the subscription folder itself was deleted. */
    fun forget(url: String) {
        book.retain(book.read().map { it.url }.filter { it != url }.toSet())
    }

    fun entry(url: String): SubscriptionEntry? = book.entry(url)

    private companion object {
        const val TAG = "LerNet.Subscription"
    }
}
