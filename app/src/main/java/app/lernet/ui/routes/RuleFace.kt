package app.lernet.ui.routes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.ConditionKind
import app.lernet.routing.PatternSign

@Composable
internal fun ruleHeadline(node: RuleNodeRecord): String {
    val privateLabel = stringResource(R.string.field_geo_private)
    val token = RulePreview.firstToken(node.shownConditions())
    val first = token?.let { (kind, raw) -> shownValue(kind, raw, privateLabel) }
    return RulePreview.displayTitle(
        node.title,
        node.isElseRule(),
        stringResource(R.string.rule_else_name),
        stringResource(R.string.rule_fallback_name),
        first,
    )
}

@Composable
internal fun RulePreviewLines(node: RuleNodeRecord, color: Color) {
    if (node.isElseRule()) return
    val privateLabel = stringResource(R.string.field_geo_private)
    val built = ArrayList<String>()
    for ((kind, values) in RulePreview.grouped(node.shownConditions())) {
        val items = previewItems(kind, values, privateLabel)
        val line = previewLine(kind, items) ?: continue
        built += line
    }
    val shown = RulePreview.cardLines(built)
    val overflow = built.size - shown.size
    Column {
        for (line in shown) {
            PreviewText(line, color)
        }
        if (overflow > 0) {
            PreviewText(stringResource(R.string.rule_preview_more, overflow), color)
        }
    }
}

@Composable
private fun PreviewText(line: String, color: Color) {
    Text(
        line,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun previewLine(kind: ConditionKind, items: List<PreviewItem>): String? {
    if (items.isEmpty()) return null
    val moreLabels = ArrayList<String>(items.size + 1)
    for (count in 0..items.size) {
        moreLabels += if (count == 0) "" else stringResource(R.string.rule_preview_more, count)
    }
    val prefix = stringResource(typeLabel(kind)) + ": "
    val budget = (RulePreview.LINE_BUDGET - prefix.length).coerceAtLeast(12)
    val line = RulePreview.fit(items, { count -> moreLabels[count] }, budget)
    if (line.isEmpty()) return null
    return prefix + line
}

@Composable
private fun previewItems(kind: ConditionKind, values: List<String>, privateLabel: String): List<PreviewItem> {
    val items = mutableListOf<PreviewItem>()
    for (raw in values) {
        val text = shownValue(kind, raw, privateLabel)
        if (text.isEmpty()) continue
        items += PreviewItem(PatternSign.negated(raw), text)
    }
    return items
}

@Composable
private fun shownValue(kind: ConditionKind, raw: String, privateLabel: String): String {
    val plain = RulePreview.plain(kind, raw, privateLabel)
    if (plain.isEmpty()) return ""
    if (!PatternSign.negated(raw)) return plain
    return stringResource(R.string.field_geo_negated, plain)
}

private fun typeLabel(kind: ConditionKind): Int = when (kind) {
    ConditionKind.DOMAIN -> R.string.rule_block_domain
    ConditionKind.GEOIP -> R.string.rule_block_geo
    ConditionKind.PRIVATE -> R.string.rule_block_private
    ConditionKind.CIDR -> R.string.rule_block_cidr
    ConditionKind.APP -> R.string.rule_block_app
    ConditionKind.PROCESS -> R.string.rule_block_app
}
