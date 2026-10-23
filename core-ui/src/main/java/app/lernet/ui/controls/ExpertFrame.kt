package app.lernet.ui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
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
 * Expert background frame (mockups v2, sheet 11): recessed amber lamps in the four chamfers.
 * Phone hugs screen edges (corner triangles only, no side rails / horizontal inset).
 * Windows keeps the full double-slot chamfered rectangle; shell pads the column.
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
    /** On windows at least [wideFrom] wide the cuts grow to [wideChamfer] (mockup 11 B on big screens). */
    val wideChamfer: Dp? = null,
    val wideFrom: Dp = 1000.dp,
    /**
     * Uniform layout padding for content so it clears chamfer cuts/lamps.
     * Null = use [chamfer], unless [contentInsetHorizontal]/[contentInsetVertical] override an axis.
     * Windows keeps 0: the desktop shell already pads its column.
     */
    val contentInset: Dp? = null,
    /** When set, horizontal content padding (phone: 0 — full usable width). */
    val contentInsetHorizontal: Dp? = null,
    /** When set, vertical content padding (phone: small — clear top/bottom lamps only). */
    val contentInsetVertical: Dp? = null,
) {
    companion object {
        // Phone: lamps hug screen edges; no side rails / horizontal inset eating width.
        val Phone = ExpertFrameSpec(
            ExpertFrameStyle.LINE_ROUND,
            inset = 0.dp,
            chamfer = 16.dp,
            lampRadius = 3.2.dp,
            glowLength = 72.dp,
            contentInsetHorizontal = 0.dp,
            contentInsetVertical = 10.dp,
        )
        val Windows = ExpertFrameSpec(
            ExpertFrameStyle.DOUBLE_SLOT,
            inset = 6.dp,
            chamfer = 26.dp,
            lampRadius = 3.0.dp,
            glowLength = 160.dp,
            wideChamfer = 34.dp,
            contentInset = 0.dp,
        )
    }
}

private val Graphite = Color(0xFF4A5368)
private val BevelPlate = Color(0xFF0A0C12)
private val BevelEdge = Color(0xFF6B5A3E)
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

/** Uniform fallback inset (legacy); prefer axis-specific resolved helpers. */
val ExpertFrameSpec.resolvedContentInset: Dp get() = contentInset ?: chamfer

val ExpertFrameSpec.resolvedContentInsetHorizontal: Dp
    get() = contentInsetHorizontal ?: contentInset ?: chamfer

val ExpertFrameSpec.resolvedContentInsetVertical: Dp
    get() = contentInsetVertical ?: contentInset ?: chamfer

/**
 * Draws the frame behind [content]. [lit] 0..1 is the lamp brightness; with [animateIn] the
 * chamfers and lamps fade in (ordinary Expert entry), otherwise they are drawn at once.
 * Content padding clears lamps: phone uses full width (0 horizontal) and a small vertical inset.
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
        Box(
            Modifier.matchParentSize().padding(
                horizontal = spec.resolvedContentInsetHorizontal,
                vertical = spec.resolvedContentInsetVertical,
            ),
            content = content,
        )
    }
}

fun DrawScope.drawExpertFrame(spec: ExpertFrameSpec, lit: Float) {
    val i = spec.inset.toPx()
    val wide = spec.wideChamfer != null && size.width >= spec.wideFrom.toPx()
    val c = (if (wide) spec.wideChamfer!! else spec.chamfer).toPx()
    val path = chamferPath(size, i, c)
    val corners = listOf(Offset(i, i), Offset(size.width - i, i), Offset(size.width - i, size.height - i), Offset(i, size.height - i))
    val reach = c + spec.glowLength.toPx() * lit.coerceAtLeast(0.05f)
    // Bevel plates: the cut-off corner reads as a dark sloped surface, so the 45° cut is visible
    // even with the lamps off.
    val w = size.width
    val h = size.height
    listOf(
        listOf(Offset(i, i), Offset(i + c, i), Offset(i, i + c)),
        listOf(Offset(w - i, i), Offset(w - i, i + c), Offset(w - i - c, i)),
        listOf(Offset(w - i, h - i), Offset(w - i - c, h - i), Offset(w - i, h - i - c)),
        listOf(Offset(i, h - i), Offset(i, h - i - c), Offset(i + c, h - i)),
    ).forEach { pts ->
        val plate = Path().apply { moveTo(pts[0].x, pts[0].y); lineTo(pts[1].x, pts[1].y); lineTo(pts[2].x, pts[2].y); close() }
        drawPath(plate, BevelPlate)
    }
    // Phone (LINE_ROUND): corner warning triangles + lamps only — no continuous side rails.
    // Windows (DOUBLE_SLOT): full chamfered frame with inner line.
    if (spec.style == ExpertFrameStyle.DOUBLE_SLOT) {
        drawPath(path, Graphite, style = Stroke(width = 1.2.dp.toPx()))
    } else {
        // Short edge stubs from each chamfer so the lamp reads as a corner marker, not a full rail.
        val stub = c * 1.35f
        val stubs = listOf(
            Offset(i + c, i) to Offset(i + c + stub, i),
            Offset(i, i + c) to Offset(i, i + c + stub),
            Offset(w - i - c, i) to Offset(w - i - c - stub, i),
            Offset(w - i, i + c) to Offset(w - i, i + c + stub),
            Offset(w - i - c, h - i) to Offset(w - i - c - stub, h - i),
            Offset(w - i, h - i - c) to Offset(w - i, h - i - c - stub),
            Offset(i + c, h - i) to Offset(i + c + stub, h - i),
            Offset(i, h - i - c) to Offset(i, h - i - c - stub),
        )
        stubs.forEach { (a, b) ->
            drawLine(Graphite.copy(alpha = 0.85f), a, b, strokeWidth = 1.2.dp.toPx())
        }
    }
    // The cut edges themselves: a warm metal line, brighter when the lamps are lit.
    val edges = listOf(
        Offset(i + c, i) to Offset(i, i + c),
        Offset(w - i - c, i) to Offset(w - i, i + c),
        Offset(w - i, h - i - c) to Offset(w - i - c, h - i),
        Offset(i, h - i - c) to Offset(i + c, h - i),
    )
    edges.forEach { (a, b) ->
        drawLine(BevelEdge, a, b, strokeWidth = 1.6.dp.toPx())
        drawLine(Amber.copy(alpha = 0.85f * lit), a, b, strokeWidth = 1.6.dp.toPx())
    }
    if (spec.style == ExpertFrameStyle.DOUBLE_SLOT) {
        corners.forEach { corner ->
            drawPath(
                path,
                Brush.radialGradient(
                    0f to Amber.copy(alpha = 0.9f * lit),
                    0.35f to AmberDeep.copy(alpha = 0.35f * lit),
                    1f to AmberDeep.copy(alpha = 0f),
                    center = corner, radius = reach,
                ),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        val inner = chamferPath(size, i + 3.5.dp.toPx(), c + 1.5.dp.toPx())
        drawPath(inner, Graphite.copy(alpha = 0.45f), style = Stroke(width = 0.6.dp.toPx()))
    }
    // Phone: no perimeter glow stroke (that recreated side rails). Lamps below carry the corner light.
    val r = spec.lampRadius.toPx() * if (wide) 1.15f else 1f
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
