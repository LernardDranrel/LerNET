package app.lernet.ui.routes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.ui.theme.LerNetWarn

/** A named channel has one block; every attached terminal rule with that name feeds it. */
internal fun channelSources(nodes: List<RuleNodeRecord>, name: String): List<RuleNodeRecord> {
    val ids = CanvasGraph.namedPipeLinks(nodes)
        .filter { it.toId == CanvasIds.pipe(name) }
        .map { CanvasIds.ruleKey(it.fromId) }
        .toSet()
    return RouteFolders.attached(nodes).filter { it.id in ids }
}

@Composable
internal fun channelSourcePath(nodes: List<RuleNodeRecord>, source: RuleNodeRecord): String {
    val byId = nodes.associateBy { it.id }
    val path = mutableListOf<String>()
    val visited = mutableSetOf<String>()
    var current: RuleNodeRecord? = source
    while (current != null && visited.add(current.id)) {
        path += ruleHeadline(current)
        current = current.parentId?.let(byId::get)
    }
    return path.asReversed().joinToString(" → ")
}

/** Two inlets joining a ring; only shown when a channel is shared. */
@Composable
internal fun ChannelPortalMark(count: Int, modifier: Modifier = Modifier) {
    val color = LerNetWarn
    val description = stringResource(R.string.route_channel_shared, count)
    Canvas(modifier.size(18.dp).semantics { contentDescription = description }) {
        val stroke = 1.7.dp.toPx()
        val mid = size.height / 2f
        val left = size.width * .08f
        val join = size.width * .47f
        val ring = Offset(size.width * .73f, mid)
        drawLine(color, Offset(left, size.height * .2f), Offset(join, mid), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, size.height * .8f), Offset(join, mid), stroke, StrokeCap.Round)
        drawLine(color, Offset(join, mid), Offset(size.width * .57f, mid), stroke, StrokeCap.Round)
        drawCircle(color, radius = size.width * .21f, center = ring, style = Stroke(stroke))
    }
}

@Composable
internal fun ChannelDetailsDialog(
    name: String,
    nodes: List<RuleNodeRecord>,
    onDismiss: () -> Unit,
) {
    val sources = channelSources(nodes, name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_channel_title, name)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sources.size > 1) ChannelPortalMark(sources.size)
                    Text(stringResource(R.string.route_channel_sources, sources.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sources.isEmpty()) {
                    Text(stringResource(R.string.route_channel_no_sources))
                } else {
                    sources.forEachIndexed { index, source ->
                        Text("${index + 1}. ${channelSourcePath(nodes, source)}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
