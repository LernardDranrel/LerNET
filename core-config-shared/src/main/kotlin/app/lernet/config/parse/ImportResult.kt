package app.lernet.config.parse

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.ProfileSource

data class ImportedProfileDraft(
    val name: String,
    val source: ProfileSource,
    val outbounds: List<NormalizedOutbound>,
    val selectedOutboundId: String,
    val subscriptionUrl: String?,
    val dnsJson: String? = null,
    /** Parts of the imported config LerNET does not use, in plain Russian, for the import screen. */
    val unusedParts: List<String> = emptyList(),
)

/** A line of a subscription or a pasted list that could not be added, with the reason in plain words. */
data class SkippedLine(val number: Int, val reason: String)

sealed class ImportResult {
    data class Success(
        val drafts: List<ImportedProfileDraft>,
        /** Lines that were not added. Never dropped silently: the import screen shows them. */
        val skipped: List<SkippedLine> = emptyList(),
        /** Hours between automatic refreshes the subscription asks for (`profile-update-interval`), if any. */
        val updateIntervalHours: Int? = null,
    ) : ImportResult() {
        /** All unused parts across drafts, without repeats. */
        val unusedParts: List<String> get() = drafts.flatMap { it.unusedParts }.distinct()
    }

    data class Failure(val errors: List<FieldError>) : ImportResult()
}

/** Texts about lines that were not added; one wording for both platforms. */
object ImportReport {
    /** «Не удалось добавить 2 ссылки: строка 3 — …; строка 5 — …», or null when everything was added. */
    fun skipped(lines: List<SkippedLine>, limit: Int = 5): String? {
        if (lines.isEmpty()) return null
        val shown = lines.take(limit).joinToString("; ") { "строка ${it.number} — ${it.reason}" }
        val more = if (lines.size > limit) "; и ещё ${lines.size - limit}" else ""
        return "Не удалось добавить ${lines.size} ${links(lines.size)}: $shown$more"
    }

    /** «ссылку / ссылки / ссылок» for [count]. */
    fun links(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> "ссылок"
            mod10 == 1 -> "ссылку"
            mod10 in 2..4 -> "ссылки"
            else -> "ссылок"
        }
    }
}
