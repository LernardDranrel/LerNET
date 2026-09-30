package app.lernet.config.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class UpdateState(
    val automatic: Boolean, val checking: Boolean = false,
    val release: ClientRelease? = null, val message: String = "Источник обновлений — стабильные релизы на GitHub",
)

class UpdateMonitor(
    private val currentVersion: String,
    private val readAutomatic: () -> Boolean,
    private val writeAutomatic: (Boolean) -> Unit,
    private val readLastCheck: () -> Long,
    private val writeLastCheck: (Long) -> Unit,
    private val fetch: () -> ClientRelease = ReleaseUpdates::latest,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val busy = AtomicBoolean(false)
    private val mutable = MutableStateFlow(UpdateState(readAutomatic()))
    val state = mutable.asStateFlow()

    fun automatic(enabled: Boolean) {
        writeAutomatic(enabled)
        mutable.value = mutable.value.copy(automatic = enabled)
    }

    suspend fun check(manual: Boolean = false) {
        val time = now()
        val age = time - readLastCheck()
        if (!manual && (!readAutomatic() || age in 0 until 86_400_000)) return
        if (!busy.compareAndSet(false, true)) return
        mutable.value = mutable.value.copy(checking = true, message = "Проверяем релизы…")
        try {
            // Throttle failures too: a blocked network must not cause a request on every opening.
            writeLastCheck(time)
            val latest = withContext(Dispatchers.IO) { fetch() }
            val available = latest.takeIf { ReleaseUpdates.isNewer(it.version, currentVersion) }
            mutable.value = mutable.value.copy(release = available,
                message = available?.let { "Доступен LerNET v${it.version}" } ?: "У вас актуальная версия LerNET v$currentVersion")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(message = "Не удалось проверить обновления. Проверьте доступ к GitHub и повторите.")
        } finally {
            mutable.value = mutable.value.copy(checking = false)
            busy.set(false)
        }
    }
}
