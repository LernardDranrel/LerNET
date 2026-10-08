package app.lernet.ui.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class NetworkLeverLamp { OFF, PENDING, HEALTHY, ERROR }

/** Dragging previews the mechanism; only acknowledged engine state lights the lamp. */
@Composable
fun NetworkLever(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accessibleName: String,
    stateLabel: String,
    lamp: NetworkLeverLamp,
    lampDescription: String,
    enabled: Boolean = true,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // Replacing the state holder cancels a preview when the caller changes state or disables input.
    var preview by remember(checked, enabled) { mutableStateOf<Float?>(null) }
    val callback by rememberUpdatedState(onCheckedChange)
    val position by animateFloatAsState(
        preview ?: if (checked) 1f else 0f,
        tween(if (reducedMotion || preview != null) 0 else 260, easing = FastOutSlowInEasing),
        label = "network-lever-position",
    )
    val currentPosition by rememberUpdatedState(position)
    val travelPx = with(LocalDensity.current) { 44.dp.toPx() }
    val lampColor by animateColorAsState(
        when (lamp) {
            NetworkLeverLamp.OFF -> Color(0xFF4B5769)
            NetworkLeverLamp.PENDING -> Color(0xFFF4C66C)
            NetworkLeverLamp.HEALTHY -> Color(0xFF80DEBE)
            NetworkLeverLamp.ERROR -> Color(0xFFFF938C)
        },
        tween(if (reducedMotion) 0 else 180), label = "network-lever-lamp",
    )
    val depth by animateFloatAsState(
        if (enabled && (pressed || preview != null)) {
            1f
        } else if (enabled && hovered) {
            .35f
        } else {
            0f
        },
        tween(if (reducedMotion) 0 else 100), label = "network-lever-grip",
    )
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.width(120.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF192538), Color(0xFF0D1522))))
            .border(
                1.dp,
                if (focused) {
                    MaterialTheme.colorScheme.primary
                } else if (hovered && enabled) {
                    Color(0xFF617590)
                } else {
                    Color(0xFF35445B)
                },
                shape,
            )
            .hoverable(interaction, enabled)
            .pointerInput(checked, enabled, travelPx) {
                if (enabled) {
                    detectVerticalDragGestures(
                        onDragStart = { preview = currentPosition },
                        onDragCancel = { preview = null },
                        onDragEnd = {
                            val target = NetworkLeverTravel.target(preview ?: currentPosition, checked)
                            preview = null
                            if (target != checked) callback(target)
                        },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            preview = NetworkLeverTravel.move(preview ?: currentPosition, delta, travelPx)
                        },
                    )
                }
            }
            .toggleable(
                value = checked, enabled = enabled, role = Role.Switch,
                interactionSource = interaction, indication = null, onValueChange = onCheckedChange,
            )
            .semantics {
                contentDescription = accessibleName
                stateDescription = "$stateLabel. $lampDescription"
            }
            .padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(116.dp, 122.dp)) {
            drawLever(position, depth, lampColor, lamp != NetworkLeverLamp.OFF)
        }
        Text(
            stateLabel,
            color = if (lamp == NetworkLeverLamp.OFF) MaterialTheme.colorScheme.onSurfaceVariant else lampColor,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        )
    }
}

private fun DrawScope.drawLever(position: Float, depth: Float, lamp: Color, lit: Boolean) {
    val sx = size.width / 116f
    val sy = size.height / 122f
    fun point(x: Float, y: Float) = Offset(x * sx, y * sy)
    fun area(w: Float, h: Float) = Size(w * sx, h * sy)
    fun rect(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 3f) =
        drawRoundRect(color, point(x, y), area(w, h), CornerRadius(r * sx))
    val dark = Color(0xFF080E18)
    val copper = Color(0xFFD79C70)
    val copperLight = Color(0xFFFFD4AC)
    val copperDark = Color(0xFF77533C)
    val panel = point(7f, 6f)
    val panelSize = area(102f, 109f)
    drawRoundRect(dark.copy(alpha = .6f), point(7f, 9f), panelSize, CornerRadius(12f * sx))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF27364C), Color(0xFF131E2E))),
        panel, panelSize, CornerRadius(12f * sx),
    )
    drawRoundRect(Color(0xFF42536B), panel, panelSize, CornerRadius(12f * sx), style = Stroke(sx))
    listOf(16f to 15f, 100f to 15f, 16f to 106f, 100f to 106f).forEach { (x, y) ->
        drawCircle(dark, 3.7f * sx, point(x, y + 1f))
        drawCircle(Color(0xFF687B93), 2.8f * sx, point(x, y))
        drawLine(Color(0xFF223044), point(x - 1.3f, y + 1.3f), point(x + 1.3f, y - 1.3f), sx)
    }
    // The separate indicator stays readable even when controls are disabled during a transition.
    drawCircle(dark, 8f * sx, point(58f, 19f))
    drawCircle(Color(0xFF63758B), 7f * sx, point(58f, 18f), style = Stroke(sx))
    if (lit) {
        drawCircle(
            Brush.radialGradient(listOf(lamp.copy(alpha = .38f), Color.Transparent), point(58f, 18f), 17f * sx),
            17f * sx, point(58f, 18f),
        )
    }
    drawCircle(
        Brush.radialGradient(listOf(lamp, lamp.copy(alpha = if (lit) .85f else .45f)), point(56f, 16f), 9f * sx),
        5f * sx, point(58f, 18f),
    )
    drawCircle(Color.White.copy(alpha = if (lit) .8f else .25f), 1.4f * sx, point(56f, 16f))

    // Vertical guide slots and end stops make the travel direction explicit.
    listOf(19f, 97f).forEach { x ->
        rect(dark, x - 2f, 38f, 4f, 57f, 2f)
        drawLine(Color(0xFF52637B), point(x - 4f, 37f), point(x + 4f, 37f), sx)
        drawLine(Color(0xFF52637B), point(x - 4f, 96f), point(x + 4f, 96f), sx)
    }
    val barY = 89f - 44f * position + depth
    listOf(34f, 58f, 82f).forEach { x ->
        rect(dark, x - 7f, 35f, 14f, 18f)
        rect(copperDark, x - 5f, 35f, 10f, 16f, 2f)
        drawLine(copperLight, point(x - 3f, 36f), point(x - 3f, 48f), 1.5f * sx)
        rect(copperDark, x - 5f, 100f, 10f, 9f, 2f)
        drawLine(dark.copy(alpha = .8f), point(x + 2f, 105f), point(x + 2f, barY + 3f), 8f * sx)
        drawLine(
            Brush.horizontalGradient(listOf(copperDark, copperLight, copper)),
            point(x, 104f), point(x, barY), 6f * sx, StrokeCap.Round,
        )
        drawLine(copperLight.copy(alpha = .6f), point(x - 1.5f, 102f), point(x - 1.5f, barY + 4f), sx)
        drawCircle(copperLight, 3f * sx, point(x, 104f))
        drawCircle(copperDark, 1.2f * sx, point(x, 104f))
    }
    rect(dark.copy(alpha = .65f), 23f, barY + 2f, 74f, 11f, 4f)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF9CABBC), Color(0xFF536680), Color(0xFF2A394F))),
        point(22f, barY - 2f), area(72f, 9f), CornerRadius(3f * sx),
    )
    drawLine(Color(0xFFC2CDDB), point(26f, barY - 1f), point(90f, barY - 1f), sx)
    // A broad, bevelled grip rather than a small icon: the entire control is draggable.
    rect(dark, 29f, barY - 12f + depth, 58f, 21f, 6f)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF586A84), Color(0xFF24354F), Color(0xFF101B2D))),
        point(29f, barY - 14f + depth), area(58f, 21f), CornerRadius(6f * sx),
    )
    drawRoundRect(
        Color(0xFF7186A4), point(29f, barY - 14f + depth), area(58f, 21f), CornerRadius(6f * sx), style = Stroke(sx),
    )
    drawLine(Color(0xFF9AACBE).copy(alpha = .6f), point(35f, barY - 12f + depth), point(81f, barY - 12f + depth), sx)
    listOf(48f, 53f, 58f, 63f, 68f).forEach { x ->
        drawLine(dark.copy(alpha = .7f), point(x, barY - 7f + depth), point(x, barY + 1f + depth), sx)
        drawLine(Color(0xFF677D99).copy(alpha = .6f), point(x + 1f, barY - 7f + depth), point(x + 1f, barY + depth), sx)
    }
}
