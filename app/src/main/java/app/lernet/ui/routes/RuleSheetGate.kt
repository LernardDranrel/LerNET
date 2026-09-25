package app.lernet.ui.routes

import app.lernet.routing.ConditionKind
import app.lernet.routing.PatternSign
import app.lernet.routing.RuleConditions

/** Blocks Done/Save when a real rule has no conditions or an empty block. */
object RuleSheetGate {
    const val EMPTY_BLOCKS = "blocks_required"

    fun emptyBlock(kind: ConditionKind): String = "block_empty:${kind.name}"

    fun errors(conditions: RuleConditions): List<String> {
        if (conditions.blocks.isEmpty()) return listOf(EMPTY_BLOCKS)
        return conditions.blocks.mapNotNull { block ->
            val filled = block.values.any { PatternSign.body(it).isNotEmpty() }
            if (filled) null else emptyBlock(block.kind)
        }
    }

    fun isIncomplete(conditions: RuleConditions): Boolean = errors(conditions).isNotEmpty()

    /** Sheet-gate tokens must not become a canvas-wide freeze banner. */
    fun isSheetOnlyError(raw: String): Boolean {
        if (raw == EMPTY_BLOCKS || raw.endsWith(":$EMPTY_BLOCKS") || raw.endsWith(EMPTY_BLOCKS)) {
            return true
        }
        return raw.contains("block_empty:")
    }

    fun kindOfEmptyBlock(raw: String): ConditionKind? {
        if (!raw.startsWith("block_empty:")) return null
        val name = raw.removePrefix("block_empty:")
        return ConditionKind.entries.firstOrNull { it.name == name }
    }
}
