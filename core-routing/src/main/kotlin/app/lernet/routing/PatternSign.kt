package app.lernet.routing

/** Leading `!` is the stored negate flag. The editor also offers a «кроме» toggle. */
object PatternSign {
    fun negated(value: String): Boolean = value.trim().startsWith("!")

    fun body(value: String): String = value.trim().removePrefix("!").trim()

    fun signed(raw: String, negated: Boolean): String {
        val bare = body(raw)
        if (bare.isEmpty()) return if (negated) "!" else ""
        return if (negated) "!$bare" else bare
    }

    /** Typing `!` at the start of the field turns negation on. The toggle can turn it off. */
    fun retainSign(stored: String, typed: String): String =
        signed(typed, negated(stored) || typed.trim().startsWith("!"))
}
