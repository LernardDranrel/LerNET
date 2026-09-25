package app.lernet.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.engine.live.LiveVia
import app.lernet.ui.theme.LerNetOk
import app.lernet.ui.theme.LerNetWarn

/** Schema, list, and diag share this palette. */
enum class RouteTone {
    VIA,
    NAMED,
    DIRECT,
    BLOCK,
}

fun routeTone(action: String, pipeName: String): RouteTone = when (action.lowercase()) {
    "direct" -> RouteTone.DIRECT
    "block" -> RouteTone.BLOCK
    "proxy" -> if (pipeName.isBlank()) RouteTone.VIA else RouteTone.NAMED
    else -> RouteTone.VIA
}

fun liveRouteTone(via: LiveVia, outbound: String, pipeLabel: String): RouteTone = when {
    outbound.equals("block", ignoreCase = true) || outbound.equals("reject", ignoreCase = true) -> RouteTone.BLOCK
    via == LiveVia.DIRECT -> RouteTone.DIRECT
    via == LiveVia.PROXY && pipeLabel != "proxy" -> RouteTone.NAMED
    via == LiveVia.PROXY -> RouteTone.VIA
    else -> RouteTone.DIRECT
}

fun RouteTone.ink(): Color = when (this) {
    RouteTone.VIA, RouteTone.NAMED -> LerNetWarn
    RouteTone.DIRECT -> LerNetOk
    RouteTone.BLOCK -> Color(0xFFFF5C5C)
}

@Composable
fun outcomeCaption(action: String, pipeName: String, branching: Boolean = false): String {
    if (branching) return stringResource(R.string.route_branch)
    val tone = routeTone(action, pipeName)
    val via = stringResource(R.string.action_proxy)
    val mode = when (tone) {
        RouteTone.NAMED -> if (branching) stringResource(R.string.action_proxy_branch) else pipeName
        RouteTone.VIA ->
            if (branching) {
                stringResource(R.string.action_proxy_branch)
            } else {
                stringResource(R.string.action_proxy_stub)
            }
        RouteTone.DIRECT -> return stringResource(R.string.action_direct)
        RouteTone.BLOCK -> return stringResource(R.string.action_block)
    }
    return "$via · $mode"
}

@Composable
internal fun OutcomeStub(action: String, pipeName: String, branching: Boolean = false) {
    val tone = routeTone(action, pipeName)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(if (branching) MaterialTheme.colorScheme.primary else tone.ink(), RoundedCornerShape(2.dp)),
        )
        Text(
            outcomeCaption(action, pipeName, branching),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
