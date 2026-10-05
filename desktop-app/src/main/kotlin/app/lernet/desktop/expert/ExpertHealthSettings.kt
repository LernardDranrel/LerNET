package app.lernet.desktop.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lernet.routing.policy.PolicyHealthSettings
import java.math.BigDecimal

/** Form units are seconds; policy and native protocol units remain exact integer milliseconds. */
internal fun healthSecondsToMillis(raw: String): Long? {
    val value = raw.trim()
    if (!Regex("[0-9]{1,2}(?:[.,][0-9]{1,3})?").matches(value)) return null
    return runCatching { BigDecimal(value.replace(',', '.')).multiply(BigDecimal(1_000)).longValueExact() }.getOrNull()
}

internal fun healthSeconds(milliseconds: Long): String =
    BigDecimal.valueOf(milliseconds, 3).stripTrailingZeros().toPlainString()

internal fun healthFormSettings(minimum: String, maximum: String, timeout: String, failures: String): PolicyHealthSettings? {
    val min = healthSecondsToMillis(minimum) ?: return null
    val max = healthSecondsToMillis(maximum) ?: return null
    val active = healthSecondsToMillis(timeout) ?: return null
    val count = failures.trim().takeIf { it.matches(Regex("[0-9]{1,2}")) }?.toIntOrNull() ?: return null
    return PolicyHealthSettings(min, max, active, count).takeIf { it.isValid() }
}

@Composable
internal fun ExpertHealthSettings(state: ExpertUiState, onIntent: (ExpertIntent) -> Unit, onDismiss: () -> Unit) {
    val original = remember { state.draft.health }
    var minimum by remember { mutableStateOf(healthSeconds(original.minimumIntervalMs)) }
    var maximum by remember { mutableStateOf(healthSeconds(original.maximumIntervalMs)) }
    var timeout by remember { mutableStateOf(healthSeconds(original.activeTimeoutMs)) }
    var failures by remember { mutableStateOf(original.failedChecksBeforeRecovery.toString()) }
    var pending by remember { mutableStateOf<ExpertDraftSubmission?>(null) }
    val settings = healthFormSettings(minimum, maximum, timeout, failures)
    val changedElsewhere = state.draft.health != original && state.draft.health != settings
    LaunchedEffect(state.draft, state.error, state.events.lastOrNull()?.id, pending) {
        when (pending?.response(state)) {
            ExpertDraftResponse.CONFIRMED -> {
                pending = null
                onDismiss()
            }
            ExpertDraftResponse.FAILED -> pending = null
            ExpertDraftResponse.WAITING, null -> Unit
        }
    }
    fun save() {
        val chosen = settings ?: return
        val updated = state.draft.copy(health = chosen)
        if (updated == state.draft && state.error == null) {
            onDismiss()
        } else {
            pending = ExpertDraftSubmission.capture(updated, state)
            onIntent(ExpertIntent.EditPolicy(updated))
        }
    }
    val readOnly = pending != null || state.busy
    ExpertModal(
        "Проверки связи", onDismiss, ::save,
        canSave = settings != null && !changedElsewhere && !readOnly,
        saveText = "Сохранить в черновик",
    ) {
        ExpertMessage(
            "Проверяем живой путь",
            "Проверки идут через активный выход. После нескольких неудач подряд LerNET восстанавливает его, " +
                "а папка с автосменой может выбрать другой доступный профиль. Одна неудача не означает, что сеть умерла.",
        )
        if (pending != null) {
            ExpertMessage("Записываем черновик", "Окно закроется после подтверждения записи. Активная сеть не меняется.")
        }
        state.error?.let { ExpertMessage("Изменение не подтверждено", it, error = true) }
        if (changedElsewhere) {
            ExpertMessage(
                "Настройки изменились вне этого окна",
                "Закройте и откройте окно снова, чтобы редактировать актуальный черновик.", warning = true,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HealthSecondsField("Минимальный интервал, секунды", minimum, readOnly) { minimum = it }
            HealthSecondsField("Максимальный интервал, секунды", maximum, readOnly) { maximum = it }
            Text(
                "Следующая проверка получает случайную задержку в этом диапазоне. Оба значения: 1–60 секунд; " +
                    "максимум не меньше минимума. Можно указать дробь, например 3,5.",
                color = ExpertColors.muted,
            )
            HealthSecondsField("Ожидание ответа активной проверки, секунды", timeout, readOnly) { timeout = it }
            Text(
                "1–15 секунд. Это время ожидания ответа, а не интервал между проверками. Большое значение " +
                    "терпимее к медленной сети, но увеличивает время обнаружения обрыва.",
                color = ExpertColors.muted,
            )
            OutlinedTextField(
                failures, { failures = it }, Modifier.fillMaxWidth(),
                label = { Text("Неудачных проверок подряд до восстановления") },
                readOnly = readOnly, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("От 1 до 10. Успешный ответ сбрасывает счётчик неудач.", color = ExpertColors.muted)
        }
        if (settings == null) {
            ExpertMessage(
                "Проверьте значения",
                "Интервал: 1–60 секунд, максимум не меньше минимума. Ожидание: 1–15 секунд. " +
                    "Число неудач: целое от 1 до 10. Для секунд допускается до трёх знаков после запятой.",
                error = true,
            )
        }
        ExpertMessage(
            "Сохранение и применение — отдельно",
            "Кнопка запишет значения в черновик. Затем сохраните схему и примените её к TUN. " +
                "Ручные проверки сервера и кандидатов используют отдельный длинный бюджет 30–45 секунд. " +
                "Случайный интервал не гарантирует незаметность туннеля для сети.",
        )
    }
}

@Composable
private fun HealthSecondsField(label: String, value: String, readOnly: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, readOnly = readOnly, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
