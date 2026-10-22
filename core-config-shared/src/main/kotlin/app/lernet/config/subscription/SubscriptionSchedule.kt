package app.lernet.config.subscription

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * When subscriptions are refreshed by themselves, the same on Android and Windows: every
 * `profile-update-interval` hours if the subscription names it, otherwise every [DEFAULT_HOURS] hours,
 * each time with a random shift of up to [MAX_JITTER_MS], so many devices do not ask the server at once.
 * After a failure the next try comes in [RETRY_MS] (plus the shift), not every few minutes.
 */
object SubscriptionSchedule {
    const val DEFAULT_HOURS = 12
    const val MIN_HOURS = 1
    const val MAX_HOURS = 24 * 7
    const val MAX_JITTER_MS = 30L * 60_000L
    const val RETRY_MS = 60L * 60_000L
    /** How often the platforms look whether a subscription is due. */
    const val TICK_MS = 15L * 60_000L

    /** Hours from the `profile-update-interval` header; null when absent or not a number. */
    fun headerHours(raw: String?): Int? {
        val value = raw?.trim()?.toDoubleOrNull() ?: return null
        if (value.isNaN() || value <= 0.0) return null
        return kotlin.math.ceil(value).toInt().coerceIn(MIN_HOURS, MAX_HOURS)
    }

    fun intervalMs(hours: Int?): Long = (hours ?: DEFAULT_HOURS).coerceIn(MIN_HOURS, MAX_HOURS) * 3_600_000L

    fun jitter(random: Random = Random.Default): Long = random.nextLong(0, MAX_JITTER_MS + 1)

    /** When [entry] should be refreshed next. */
    fun dueAt(entry: SubscriptionEntry): Long {
        val base = if (entry.failedSinceSuccess && entry.lastAttemptMs > 0) entry.lastAttemptMs + RETRY_MS
        else entry.lastSuccessMs + intervalMs(entry.intervalHours)
        return base + entry.jitterMs
    }

    fun isDue(entry: SubscriptionEntry, nowMs: Long): Boolean = nowMs >= dueAt(entry)
}

/** What LerNET remembers about one subscription address. Kept apart from configs; the address itself is secret (it carries the access key), so the file stays in app storage only. */
@Serializable
data class SubscriptionEntry(
    val url: String,
    val intervalHours: Int? = null,
    val lastSuccessMs: Long = 0L,
    val lastAttemptMs: Long = 0L,
    val failedSinceSuccess: Boolean = false,
    val jitterMs: Long = 0L,
    /** Last result in plain words, for the screen. */
    val lastMessage: String = "",
    /** Origin of every config this subscription wrote, by config id. */
    val origins: Map<String, SubscriptionOrigin> = emptyMap(),
)

@Serializable
data class SubscriptionBookData(val entries: List<SubscriptionEntry> = emptyList())

/** All subscriptions in one small JSON file (Android: app files; Windows: the LerNET data folder). */
class SubscriptionBook(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Synchronized
    fun read(): List<SubscriptionEntry> = runCatching {
        if (!Files.exists(file)) emptyList() else json.decodeFromString<SubscriptionBookData>(Files.readString(file)).entries
    }.getOrDefault(emptyList())

    @Synchronized
    fun entry(url: String): SubscriptionEntry? = read().firstOrNull { it.url == url }

    @Synchronized
    fun update(url: String, change: (SubscriptionEntry) -> SubscriptionEntry): SubscriptionEntry {
        val all = read()
        val current = all.firstOrNull { it.url == url } ?: SubscriptionEntry(url)
        val next = change(current)
        write(all.filterNot { it.url == url } + next)
        return next
    }

    /** Forget subscriptions no config uses any more. */
    @Synchronized
    fun retain(urls: Set<String>) {
        val all = read()
        val kept = all.filter { it.url in urls }
        if (kept.size != all.size) write(kept)
    }

    private fun write(entries: List<SubscriptionEntry>) {
        file.parent?.let(Files::createDirectories)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, json.encodeToString(SubscriptionBookData(entries)))
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
