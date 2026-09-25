package app.lernet.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared Material 3 layout tokens — screens must not invent sizes. */
object LerNetDimens {
    val screenPadding: Dp = 16.dp
    val sectionGap: Dp = 16.dp
    val itemGap: Dp = 8.dp
    val contentPadding: Dp = 16.dp
    val buttonMinHeight: Dp = 48.dp

    /** Connect / Save / Import only. */
    val primaryActionMinHeight: Dp = 56.dp
    val iconButtonSize: Dp = 48.dp
    val cardGap: Dp = 8.dp

    /** Country catalog scrolls inside the rule sheet. */
    val catalogListHeight: Dp = 320.dp

    val contentPaddingValues: PaddingValues = PaddingValues(contentPadding)
}

val LerNetShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

fun Modifier.lernetButton(): Modifier =
    this
        .heightIn(min = LerNetDimens.buttonMinHeight)
        .defaultMinSize(minHeight = LerNetDimens.buttonMinHeight)

fun Modifier.lernetPrimaryAction(): Modifier =
    this
        .fillMaxWidth()
        .heightIn(min = LerNetDimens.primaryActionMinHeight)
        .defaultMinSize(minHeight = LerNetDimens.primaryActionMinHeight)

@Composable
fun lernetButtonPadding(): PaddingValues =
    PaddingValues(horizontal = LerNetDimens.contentPadding, vertical = 10.dp)
