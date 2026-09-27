package app.lernet.ui.home

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.engine.ConnectionState
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.layout.rememberCompactMetrics
import app.lernet.ui.motion.motionTween
import app.lernet.ui.motion.rememberReduceMotion
import app.lernet.ui.theme.LerNetOk

/** The connection action stays in the first viewport, with one clear session status. */
@Composable
internal fun ConnectionHero(
    connection: ConnectionState,
    status: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenRoutes: (() -> Unit)?,
    profile: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val compact = rememberCompactMetrics().compact
    val reduceMotion = rememberReduceMotion()
    val connecting = connection == ConnectionState.CONNECTING || connection == ConnectionState.RECONNECTING
    val active = connecting || connection == ConnectionState.CONNECTED
    val accent by animateColorAsState(
        when (connection) {
            ConnectionState.CONNECTED -> LerNetOk
            ConnectionState.FAILED -> scheme.error
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.DISCONNECTED -> scheme.primary
        },
        animationSpec = motionTween(reduceMotion, 240),
        label = "power-accent",
    )
    val action = stringResource(if (active) R.string.disconnect else R.string.connect)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val depth by animateFloatAsState(
        targetValue = if (!enabled) 0f else if (pressed) 1f else if (hovered) 0.45f else 0f,
        animationSpec = motionTween(reduceMotion, 160),
        label = "power-depth",
    )
    var lockedUntil by remember { mutableLongStateOf(0L) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.35f), scheme.surface)))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_connection_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = { onOpenRoutes?.invoke() },
                    enabled = onOpenRoutes != null,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(
                        LerNetSymbols.route(),
                        contentDescription = stringResource(R.string.home_open_routes),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Box(
                Modifier.size(if (compact) 166.dp else 190.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.17f), Color.Transparent)))
                    drawCircle(accent.copy(alpha = 0.09f), radius = size.minDimension * 0.46f, style = Stroke(1.dp.toPx()))
                    drawCircle(scheme.background.copy(alpha = 0.7f), radius = size.minDimension * 0.41f)
                }
                Box(
                    modifier = Modifier
                        .size(if (compact) 124.dp else 142.dp)
                        .graphicsLayer {
                            scaleX = 1f - depth * 0.065f
                            scaleY = 1f - depth * 0.065f
                            translationY = depth * 3.dp.toPx()
                        }
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    if (active) accent.copy(alpha = 0.18f) else scheme.surfaceContainerHigh,
                                    scheme.surfaceContainerLowest,
                                ),
                            ),
                        )
                        .border(
                            if (focused) 3.dp else 2.dp,
                            accent.copy(alpha = if (enabled) 0.7f - depth * 0.2f else 0.2f),
                            CircleShape,
                        )
                        .hoverable(interaction, enabled = enabled)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = {
                                val now = SystemClock.uptimeMillis()
                                if (now >= lockedUntil) {
                                    lockedUntil = now + 320
                                    onToggle()
                                }
                            },
                        )
                        .semantics {
                            contentDescription = action
                            stateDescription = status
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        LerNetSymbols.power(), contentDescription = null, modifier = Modifier.size(52.dp),
                        tint = if (enabled) accent else scheme.onSurfaceVariant,
                    )
                }
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(if (compact) 140.dp else 158.dp),
                        color = accent,
                        strokeWidth = 2.dp,
                        trackColor = Color.Transparent,
                    )
                }
            }
            Text(
                status,
                style = MaterialTheme.typography.headlineMedium,
                color = if (connection == ConnectionState.DISCONNECTED) scheme.onSurface else accent,
                textAlign = TextAlign.Center,
            )
            Text(
                if (enabled) action else stringResource(R.string.home_choose_before_connect),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            profile()
        }
    }
}
