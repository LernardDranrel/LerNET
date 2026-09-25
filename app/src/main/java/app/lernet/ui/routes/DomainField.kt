package app.lernet.ui.routes

import app.lernet.routing.DomainPatterns
import app.lernet.routing.PatternSign

/**
 * One human field for exact domains and globs.
 * No `*` → exact domain. A token that contains `*` → domain suffix (leading `*.` / `*` stripped).
 */
object DomainField {
    fun join(domains: List<String>, suffixes: List<String>): String =
        DomainPatterns.join(domains, suffixes).joinToString(", ")

    fun split(raw: String): Pair<List<String>, List<String>> =
        DomainPatterns.split(raw.split(',', '\n', ' ', '\t'))
}

object GeoIpCodes {
    /** Country tap cycles empty → included → excluded → empty. */
    fun select(current: List<String>, code: String): List<String> {
        val stored = stored(current, code)
        return when {
            stored == null -> current + code
            !PatternSign.negated(stored) -> rewrite(current, code, "!$code")
            else -> clear(current, code)
        }
    }

    /** Adds «не …», or flips an existing code. The model keeps a leading `!`. */
    fun negate(current: List<String>, code: String): List<String> {
        val stored = stored(current, code)
        val mark = "!$code"
        return when {
            stored == null -> current + mark
            PatternSign.negated(stored) -> rewrite(current, code, code)
            else -> rewrite(current, code, mark)
        }
    }

    fun clear(current: List<String>, code: String): List<String> =
        current.filterNot { PatternSign.body(it).equals(code, ignoreCase = true) }

    private fun stored(current: List<String>, code: String): String? =
        current.firstOrNull { PatternSign.body(it).equals(code, ignoreCase = true) }

    private fun rewrite(current: List<String>, code: String, next: String): List<String> =
        current.map { item -> if (PatternSign.body(item).equals(code, ignoreCase = true)) next else item }
}
