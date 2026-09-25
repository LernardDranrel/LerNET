package app.lernet.config.parse

import app.lernet.config.model.ProfileSource
import app.lernet.config.net.RemoteTextFetcher

class ImportCoordinator(
    private val fetcher: RemoteTextFetcher,
) {
    fun importVless(link: String): ImportResult = VlessParser.parse(link)

    fun importPastedJson(raw: String): ImportResult =
        JsonConfigParser.parse(raw, ProfileSource.JSON_PASTE, defaultName = "JSON")

    fun importJsonUrl(url: String): ImportResult {
        val checked = validateHttpUrl(url, field = "jsonUrl") ?: return fetchAndParseJson(url)
        return ImportResult.Failure(listOf(checked))
    }

    fun classifyUrl(url: String): ImportGuess = when (val fetched = fetcher.fetch(url.trim())) {
        is RemoteTextFetcher.Result.Ok -> ImportHint.classifyFetched(fetched.contentType, fetched.body)
        is RemoteTextFetcher.Result.Err -> ImportGuess(mode = null, soft = SoftHint.MAYBE_SUBSCRIPTION)
    }

    fun importSubscriptionDocument(raw: String): ImportResult = SubscriptionParser.parse(raw, subscriptionUrl = "")

    fun importSubscription(url: String): ImportResult {
        val checked = validateHttpUrl(url, field = "subscriptionUrl")
        if (checked != null) return ImportResult.Failure(listOf(checked))
        return when (val fetched = fetcher.fetch(url)) {
            is RemoteTextFetcher.Result.Ok -> SubscriptionParser.parse(fetched.body, url)
            is RemoteTextFetcher.Result.Err ->
                ImportResult.Failure(listOf(FieldError("subscriptionUrl", fetched.message)))
        }
    }

    private fun fetchAndParseJson(url: String): ImportResult = when (val fetched = fetcher.fetch(url)) {
        is RemoteTextFetcher.Result.Ok ->
            JsonConfigParser.parse(fetched.body, ProfileSource.JSON_URL, defaultName = url, subscriptionUrl = url)
        is RemoteTextFetcher.Result.Err ->
            ImportResult.Failure(listOf(FieldError("jsonUrl", fetched.message)))
    }

    fun withDisplayName(result: ImportResult, displayName: String): ImportResult {
        val name = displayName.trim()
        if (name.isEmpty() || result !is ImportResult.Success || result.drafts.size != 1) {
            return result
        }
        return ImportResult.Success(result.drafts.map { it.copy(name = name) })
    }

    private fun validateHttpUrl(url: String, field: String): FieldError? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return FieldError(field, "URL пуст")
        if (!trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return FieldError(field, "URL должен начинаться с http:// или https://")
        }
        return null
    }
}
