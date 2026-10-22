package app.lernet.subscription

import android.content.Context
import app.lernet.config.parse.ImportCoordinator
import app.lernet.config.parse.ImportResult
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.subscription.SubscriptionBook
import app.lernet.config.subscription.SubscriptionEntry
import app.lernet.config.subscription.SubscriptionSchedule
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
            when (val prepared = updater.prepare(url, repository.subscribedConfigs(url, active))) {
                is SubscriptionUpdater.Prepared.Failed -> prepared.message.also { LerNetLog.w(TAG, "refresh failed: $it") }
                is SubscriptionUpdater.Prepared.Ready -> try {
                    val policy = settings.settings.first().defaultDnsPolicy
                    val added = repository.applySubscriptionPlan(url, prepared.plan, policy)
                    updater.applied(prepared, added)
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

    /** First import: remember the interval and what each config looked like, to keep user edits later. */
    fun imported(result: ImportResult.Success, ids: List<String>) {
        val url = result.drafts.firstOrNull()?.subscriptionUrl?.takeIf(String::isNotBlank) ?: return
        runCatching { updater.imported(url, result, ids) }
            .onFailure { LerNetLog.w(TAG, "subscription book write failed: ${it.message}") }
    }

    fun entry(url: String): SubscriptionEntry? = book.entry(url)

    private companion object {
        const val TAG = "LerNet.Subscription"
    }
}
