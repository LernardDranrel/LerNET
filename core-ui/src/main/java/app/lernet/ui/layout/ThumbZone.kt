package app.lernet.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Space under a primary thumb CTA so the label sits above the system nav bar.
 *
 * Follow the actual system inset, then leave a small thumb-safe gap. A fixed
 * navigation-bar floor wastes space on gesture-navigation phones.
 */
@Composable
fun rememberThumbZoneBottomPadding(): Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tappable = WindowInsets.tappableElement.asPaddingValues().calculateBottomPadding()
    val gestures = WindowInsets.mandatorySystemGestures.asPaddingValues().calculateBottomPadding()
    val inset = maxOf(nav, tappable, gestures)
    return inset + 12.dp
}
