package app.lernet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import app.lernet.ui.motion.rememberReduceMotion
import app.lernet.ui.theme.LerNetBlack
import app.lernet.ui.theme.LerNetMuted
import app.lernet.ui.theme.LerNetOk
import app.lernet.ui.theme.LerNetPanel
import app.lernet.ui.theme.LerNetWarn

@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        targetValue = when (tone) {
            StatusTone.Idle -> LerNetPanel
            StatusTone.Progress -> LerNetWarn
            StatusTone.Ok -> LerNetOk
            StatusTone.Danger -> scheme.errorContainer
        },
        animationSpec = tween(if (reduceMotion) 0 else 240),
        label = "pill-bg",
    )
    val fg by animateColorAsState(
        targetValue = when (tone) {
            StatusTone.Idle -> LerNetMuted
            StatusTone.Progress -> LerNetBlack
            StatusTone.Ok -> LerNetBlack
            StatusTone.Danger -> scheme.onErrorContainer
        },
        animationSpec = tween(if (reduceMotion) 0 else 240),
        label = "pill-fg",
    )
    val scale =
        if (tone == StatusTone.Progress && !reduceMotion) {
            val pulse = rememberInfiniteTransition(label = "pill-pulse")
            val animated by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 1.03f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "pill-scale",
            )
            animated
        } else {
            1f
        }
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

enum class StatusTone {
    Idle,
    Progress,
    Ok,
    Danger,
}
