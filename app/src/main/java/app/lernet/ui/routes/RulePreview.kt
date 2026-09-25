package app.lernet.ui.routes

import app.lernet.routing.ConditionKind
import app.lernet.routing.PatternSign
import app.lernet.routing.RuleConditions

/** Card and list text. Routing does not read this. */
internal object RulePreview {
    /** Value text that still fits about two lines beside a short type label. */
    const val LINE_BUDGET = 56

    /** Kind lines on one card. Anything past this becomes «и ещё N». */
    const val CARD_LINES = 2

    fun displayTitle(
        stored: String,
        elseRule: Boolean,
        elseLabel: String,
        fallbackLabel: String,
        first: String?,
    ): String {
        if (elseRule) return elseLabel
        val custom = stored.trim()
        if (custom.isNotEmpty()) return custom
        return first ?: fallbackLabel
    }

    fun grouped(conditions: RuleConditions): List<Pair<ConditionKind, List<String>>> {
        val order = mutableListOf<ConditionKind>()
        val values = linkedMapOf<ConditionKind, MutableList<String>>()
        for (block in conditions.blocks) {
            val present = block.values.filter { PatternSign.body(it).isNotEmpty() }
            if (present.isEmpty()) continue
            if (block.kind !in values) order += block.kind
            values.getOrPut(block.kind) { mutableListOf() }.addAll(present)
        }
        return order.map { kind -> kind to values.getValue(kind) }
    }

    fun firstToken(conditions: RuleConditions): Pair<ConditionKind, String>? {
        for (block in conditions.blocks) {
            val raw = block.values.firstOrNull { PatternSign.body(it).isNotEmpty() } ?: continue
            return block.kind to raw
        }
        return null
    }

    fun plain(kind: ConditionKind, raw: String, privateLabel: String): String {
        val body = PatternSign.body(raw)
        if (body.isEmpty()) return ""
        return when (kind) {
            ConditionKind.GEOIP ->
                if (body.equals("private", ignoreCase = true)) privateLabel else body.uppercase()
            ConditionKind.PRIVATE -> privateLabel
            ConditionKind.DOMAIN -> body
            ConditionKind.CIDR -> body
            ConditionKind.APP -> body
            ConditionKind.PROCESS -> body
        }
    }

    /**
     * Negated values are reserved before affirmative ones, then shown in the original order.
     * A value that does not fit is counted in [more], not dropped without that count.
     */
    fun fit(items: List<PreviewItem>, more: (Int) -> String, budget: Int = LINE_BUDGET): String {
        if (items.isEmpty()) return ""
        val picked = mutableListOf<Int>()
        val preferNegated = items.indices.sortedBy { index -> if (items[index].negated) 0 else 1 }
        for (index in preferNegated) {
            val trial = (picked + index).sorted()
            if (picked.isEmpty() || raw(items, trial, more).length <= budget) {
                picked += index
            }
        }
        return clip(items, picked.sorted(), more, budget)
    }

    /** Two kind lines stay. A third kind is not drawn here; the card adds «и ещё N» itself. */
    fun cardLines(lines: List<String>): List<String> =
        if (lines.size <= CARD_LINES) lines else listOf(lines.first())

    private fun raw(items: List<PreviewItem>, indexes: List<Int>, more: (Int) -> String): String {
        val body = indexes.joinToString(", ") { items[it].text }
        val hidden = items.size - indexes.size
        if (hidden == 0) return body
        return "$body, ${more(hidden)}"
    }

    private fun clip(items: List<PreviewItem>, indexes: List<Int>, more: (Int) -> String, budget: Int): String {
        val text = raw(items, indexes, more)
        val hidden = items.size - indexes.size
        if (hidden == 0 || text.length <= budget) return text
        val suffix = ", ${more(hidden)}"
        val room = (budget - suffix.length).coerceAtLeast(1)
        val body = indexes.joinToString(", ") { items[it].text }
        return body.take(room).trimEnd() + "…" + suffix
    }
}

internal data class PreviewItem(val negated: Boolean, val text: String)
