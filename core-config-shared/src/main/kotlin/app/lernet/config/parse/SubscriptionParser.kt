package app.lernet.config.parse

import app.lernet.config.model.ProfileSource

object SubscriptionParser {
    fun parse(body: String, subscriptionUrl: String, updateIntervalHours: Int? = null): ImportResult {
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
                ).withInterval(updateIntervalHours)
            else -> null
        }
        if (jsonOrEmpty != null) return jsonOrEmpty
        val decoded = decodeMaybeBase64(trimmed)
        val lines = decoded.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
        if (lines.isEmpty()) {
            return ImportResult.Failure(listOf(FieldError("subscription", "В подписке нет серверов")))
        }
        val drafts = mutableListOf<ImportedProfileDraft>()
        val skipped = mutableListOf<SkippedLine>()
        lines.forEachIndexed { index, line ->
            when (val result = parseLine(line, index, subscriptionUrl)) {
                is ImportResult.Success -> drafts += result.drafts
                is ImportResult.Failure -> skipped += SkippedLine(index + 1, result.errors.joinToString("; ") { it.message })
            }
        }
        if (drafts.isEmpty()) {
            val reason = ImportReport.skipped(skipped) ?: "Не удалось разобрать ни одного сервера"
            return ImportResult.Failure(listOf(FieldError("subscription", reason)))
        }
        return ImportResult.Success(drafts, skipped, updateIntervalHours)
    }

    private fun parseLine(line: String, index: Int, subscriptionUrl: String): ImportResult = when {
        ShareLinkParser.isShareLink(line) ->
            ShareLinkParser.parse(line).withSubscription(subscriptionUrl)
        line.startsWith("{") ->
            JsonConfigParser.parse(
                raw = line,
                source = ProfileSource.SUBSCRIPTION,
                defaultName = "Сервер ${index + 1}",
                subscriptionUrl = subscriptionUrl,
            )
        else -> {
            val scheme = line.substringBefore("://", "").takeIf { it.isNotBlank() && it.length <= 16 && line.contains("://") }
            ImportResult.Failure(
                listOf(
                    FieldError(
                        "line[$index]",
                        if (scheme != null) "ссылки «$scheme://» не поддерживаются" else "строка не похожа ни на ссылку на сервер, ни на JSON-конфиг",
                    ),
                ),
            )
        }
    }

    private fun ImportResult.withSubscription(url: String): ImportResult =
        when (this) {
            is ImportResult.Success ->
                copy(drafts = drafts.map { it.copy(source = ProfileSource.SUBSCRIPTION, subscriptionUrl = url.ifBlank { null }) })
            is ImportResult.Failure -> this
        }

    private fun ImportResult.withInterval(hours: Int?): ImportResult =
        if (this is ImportResult.Success) copy(updateIntervalHours = hours) else this

    internal fun decodeMaybeBase64(raw: String): String {
        val compact = raw.replace("\\s".toRegex(), "")
        val looksBase64 = compact.matches(Regex("^[A-Za-z0-9+/=_-]+$"))
        if (!looksBase64) return raw
        val decoded = ShareLinkParser.decodeBase64(compact)
        return decoded?.takeIf { ShareLinkParser.countLinks(it) > 0 || it.contains("{") } ?: raw
    }
}
