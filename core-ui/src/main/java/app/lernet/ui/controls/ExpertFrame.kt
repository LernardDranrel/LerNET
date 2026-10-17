package app.lernet.ui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Expert background frame (mockups v2, sheet 11): a chamfered rectangle in graphite with recessed
 * amber lamps in the four chamfers. It is drawn behind the content and never takes layout space.
 */
enum class ExpertFrameStyle {
    /** Phone, variant A: thin line + round lamp. */
    LINE_ROUND,
    /** Windows, variant B: double line + slot lamp. */
    DOUBLE_SLOT,
}

data class ExpertFrameSpec(
    val style: ExpertFrameStyle,
    val inset: Dp,
    val chamfer: Dp,
    val lampRadius: Dp,
    val glowLength: Dp,
) {
    companion object {
        val Phone = ExpertFrameSpec(ExpertFrameStyle.LINE_ROUND, inset = 4.dp, chamfer = 20.dp, lampRadius = 2.6.dp, glowLength = 110.dp)
        val Windows = ExpertFrameSpec(ExpertFrameStyle.DOUBLE_SLOT, inset = 6.dp, chamfer = 18.dp, lampRadius = 2.2.dp, glowLength = 140.dp)
    }
}

private val Graphite = Color(0xFF2E3548)
private val Amber = Color(0xFFF5C16C)
private val AmberDeep = Color(0xFFD79C70)
private val LampCore = Color(0xFFFFF1CF)
private val LampDark = Color(0xFF9A6526)
private val LampOff = Color(0xFF17140F)
private val Recess = Color(0xFF05060A)

/** Chamfered rectangle path with inset [i] and 45° corner cut [c] (px). */
fun chamferPath(size: Size, i: Float, c: Float): Path = Path().apply {
    val w = size.width
    val h = size.height
    moveTo(i + c, i)
    lineTo(w - i - c, i)
    lineTo(w - i, i + c)
    lineTo(w - i, h - i - c)
    lineTo(w - i - c, h - i)
    lineTo(i + c, h - i)
    lineTo(i, h - i - c)
    lineTo(i, i + c)
    close()
}

/** Centres of the four chamfers, where the lamps sit (px). */
fun chamferLampCentres(size: Size, i: Float, c: Float): List<Offset> {
    val m = c / 2f
    return listOf(
        Offset(i + m, i + m),
        Offset(size.width - i - m, i + m),
        Offset(size.width - i - m, size.height - i - m),
        Offset(i + m, size.height - i - m),
    )
}

/**
 * Draws the frame behind [content]. [lit] 0..1 is the lamp brightness; with [animateIn] the
 * chamfers and lamps fade in (ordinary Expert entry), otherwise they are drawn at once.
 */
@Composable
fun ExpertFramedBox(
    spec: ExpertFrameSpec,
    modifier: Modifier = Modifier,
    lit: Float = 1f,
    animateIn: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val glow = remember { Animatable(if (animateIn) 0f else lit) }
    LaunchedEffect(lit, animateIn) {
        if (animateIn) glow.animateTo(lit, tween(durationMillis = 900, easing = FastOutSlowInEasing)) else glow.snapTo(lit)
    }
    Box(modifier) {
        Canvas(Modifier.matchParentSize().testTag("expert_frame")) { drawExpertFrame(spec, glow.value) }
        content()
    }
}

fun DrawScope.drawExpertFrame(spec: ExpertFrameSpec, lit: Float) {
    val i = spec.inset.toPx()
    val c = spec.chamfer.toPx()
    val path = chamferPath(size, i, c)
    val corners = listOf(Offset(i, i), Offset(size.width - i, i), Offset(size.width - i, size.height - i), Offset(i, size.height - i))
    val reach = c + spec.glowLength.toPx() * lit.coerceAtLeast(0.05f)
    drawPath(path, Graphite.copy(alpha = 0.85f), style = Stroke(width = 1.dp.toPx()))
    corners.forEach { corner ->
        drawPath(
            path,
            Brush.radialGradient(
                0f to Amber.copy(alpha = 0.75f * lit),
                0.35f to AmberDeep.copy(alpha = 0.35f * lit),
                1f to AmberDeep.copy(alpha = 0f),
                center = corner, radius = reach,
            ),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    if (spec.style == ExpertFrameStyle.DOUBLE_SLOT) {
        val inner = chamferPath(size, i + 3.5.dp.toPx(), c + 1.5.dp.toPx())
        drawPath(inner, Graphite.copy(alpha = 0.45f), style = Stroke(width = 0.6.dp.toPx()))
    }
    val r = spec.lampRadius.toPx()
    chamferLampCentres(size, i, c).forEachIndexed { index, centre ->
        val angle = if (index % 2 == 0) -45f else 45f
        translate(centre.x, centre.y) {
            rotate(angle, pivot = Offset.Zero) {
                drawCircle(Brush.radialGradient(listOf(Amber.copy(alpha = 0.55f * lit), Amber.copy(alpha = 0f)), center = Offset.Zero, radius = r * 3.4f), radius = r * 3.4f, center = Offset.Zero)
                when (spec.style) {
                    ExpertFrameStyle.LINE_ROUND -> {
                        drawCircle(Recess, radius = r + 1.8.dp.toPx(), center = Offset.Zero)
                        drawCircle(LampOff, radius = r, center = Offset.Zero)
                        drawCircle(Brush.radialGradient(listOf(LampCore, Amber, LampDark), center = Offset(-r * 0.2f, -r * 0.25f), radius = r * 1.4f), radius = r, center = Offset.Zero, alpha = lit)
                    }
                    ExpertFrameStyle.DOUBLE_SLOT -> {
                        val a = r * 2.6f
                        val b = r * 0.95f
                        val pad = 1.8.dp.toPx()
                        drawRoundRect(Recess, topLeft = Offset(-a - pad, -b - pad), size = Size(2 * (a + pad), 2 * (b + pad)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(b + pad))
                        drawRoundRect(LampOff, topLeft = Offset(-a, -b), size = Size(2 * a, 2 * b), cornerRadius = androidx.compose.ui.geometry.CornerRadius(b))
                        drawRoundRect(Brush.radialGradient(listOf(LampCore, Amber, LampDark), center = Offset.Zero, radius = a), topLeft = Offset(-a, -b), size = Size(2 * a, 2 * b), cornerRadius = androidx.compose.ui.geometry.CornerRadius(b), alpha = lit)
                    }
                }
            }
        }
    }
}
