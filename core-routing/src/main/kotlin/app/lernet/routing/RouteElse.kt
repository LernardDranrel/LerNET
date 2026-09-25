package app.lernet.routing

/** Every level ends in enabled «Иначе», which may itself branch into another complete level. */
object RouteElse {
    const val MISSING = "На этом уровне нет «Иначе». Пустое условие должно быть последним."
    const val NOT_LAST = "«Иначе» должно быть последним номером приоритета."
    const val DISABLED = "«Иначе» должно быть включено."
    const val EXTRA = "На уровне может быть только одно «Иначе»."
    const val BLANK = "У правила нет условия. Пустое условие остаётся только у «Иначе»."
    const val PIPE_AND_FORK = "На одном узле «В обход» либо труба, либо потомки — не оба."
    const val LOSS_CHILDREN = "Потомки этого узла будут удалены."
    const val LOSS_PIPE = "Имя трубы будет стёрто."
    const val LOSS_BOTH = "Имя трубы будет стёрто, потомки удалены."

    fun isElse(node: RuleNode): Boolean {
        if (!node.match.isCatchAll()) return false
        // Null conditions come from blank blocksJson — the seeded «Иначе».
        // Empty encoded blocks are an unfinished real rule (see [BLANK]).
        return node.conditions == null
    }

    fun errors(attached: List<RuleNode>): List<FieldError> {
        if (attached.isEmpty()) return emptyList()
        return attached.groupBy { it.parentId }.flatMap { (_, siblings) -> groupErrors(siblings) }
    }

    fun blankRules(nodes: List<RuleNode>): List<FieldError> = nodes.mapNotNull { node ->
        if (isElse(node) || !node.match.isCatchAll()) {
            null
        } else {
            FieldError(node.id, "match", BLANK)
        }
    }

    private fun groupErrors(siblings: List<RuleNode>): List<FieldError> {
        val ordered = siblings.sortedWith(compareBy<RuleNode> { it.sortIndex }.thenBy { it.id })
        val elses = ordered.filter { isElse(it) }
        val only = elses.singleOrNull()
        return when {
            elses.isEmpty() -> listOf(FieldError(ordered.first().id, "else", MISSING))
            only == null -> listOf(FieldError(elses.last().id, "else", EXTRA))
            ordered.last().id != only.id -> listOf(FieldError(only.id, "else", NOT_LAST))
            !only.enabled -> listOf(FieldError(only.id, "else", DISABLED))
            else -> emptyList()
        }
    }
}
