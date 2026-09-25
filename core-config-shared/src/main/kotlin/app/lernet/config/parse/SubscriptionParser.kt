package app.lernet.config.parse

import app.lernet.config.model.ProfileSource
import java.util.Base64

object SubscriptionParser {
    fun parse(body: String, subscriptionUrl: String): ImportResult {
        val trimmed = body.trim()
        val jsonOrEmpty = when {
            trimmed.isEmpty() ->
                ImportResult.Failure(listOf(FieldError("subscription", "Пустой ответ подписки")))
            trimmed.startsWith("{") || trimmed.startsWith("[") ->
                JsonConfigParser.parse(
                    raw = trimmed,
                    source = ProfileSource.SUBSCRIPTION,
                    defaultName = "Подписка",
                    subscriptionUrl = subscriptionUrl,
                )
            else -> null
        }
        if (jsonOrEmpty != null) return jsonOrEmpty
        val decoded = decodeMaybeBase64(trimmed)
        val lines = decoded.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) {
            return ImportResult.Failure(listOf(FieldError("subscription", "В подписке нет узлов")))
        }
        val drafts = mutableListOf<ImportedProfileDraft>()
        val errors = mutableListOf<FieldError>()
        lines.forEachIndexed { index, line ->
            parseLine(line, index, subscriptionUrl, drafts, errors)
        }
        if (drafts.isEmpty()) {
            return ImportResult.Failure(
                errors.ifEmpty { listOf(FieldError("subscription", "Не удалось разобрать ни одного узла")) },
            )
        }
        return ImportResult.Success(drafts)
    }

    private fun parseLine(
        line: String,
        index: Int,
        subscriptionUrl: String,
        drafts: MutableList<ImportedProfileDraft>,
        errors: MutableList<FieldError>,
    ) {
        val result = when {
            line.startsWith("vless://", ignoreCase = true) ->
                VlessParser.parse(line).prefixed(index).withSubscription(subscriptionUrl)
            line.startsWith("{") ->
                JsonConfigParser.parse(
                    raw = line,
                    source = ProfileSource.SUBSCRIPTION,
                    defaultName = "Узел ${index + 1}",
                    subscriptionUrl = subscriptionUrl,
                ).prefixed(index)
            else -> ImportResult.Failure(
                listOf(FieldError("line[$index]", "MVP принимает только vless:// или JSON outbound")),
            )
        }
        when (result) {
            is ImportResult.Success -> drafts += result.drafts
            is ImportResult.Failure -> errors += result.errors
        }
    }

    private fun ImportResult.prefixed(index: Int): ImportResult =
        when (this) {
            is ImportResult.Success -> this
            is ImportResult.Failure ->
                ImportResult.Failure(errors.map { it.copy(field = "line[$index].${it.field}") })
        }

    private fun ImportResult.withSubscription(url: String): ImportResult =
        when (this) {
            is ImportResult.Success ->
                ImportResult.Success(drafts.map { it.copy(source = ProfileSource.SUBSCRIPTION, subscriptionUrl = url) })
            is ImportResult.Failure -> this
        }

    internal fun decodeMaybeBase64(raw: String): String {
        val compact = raw.replace("\\s".toRegex(), "")
        val looksBase64 = compact.matches(Regex("^[A-Za-z0-9+/=_-]+$")) && compact.length % 4 == 0
        if (!looksBase64) return raw
        val padded = compact.replace('-', '+').replace('_', '/')
            .let { text ->
                val pad = (4 - text.length % 4) % 4
                text + "=".repeat(pad)
            }
        val decoded = runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
        return decoded?.takeIf { it.contains("vless://", ignoreCase = true) || it.contains("{") } ?: raw
    }
}
