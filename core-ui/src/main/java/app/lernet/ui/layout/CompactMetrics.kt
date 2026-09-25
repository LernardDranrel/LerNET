package app.lernet.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import app.lernet.ui.theme.LerNetDimens

data class CompactMetrics(
    val gutter: Dp,
    val compact: Boolean,
)

@Composable
fun rememberCompactMetrics(): CompactMetrics {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        val compact = configuration.screenWidthDp < 400 || configuration.screenHeightDp < 680
        CompactMetrics(gutter = LerNetDimens.screenPadding, compact = compact)
    }
}
