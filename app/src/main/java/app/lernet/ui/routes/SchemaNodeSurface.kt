package app.lernet.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import app.lernet.ui.theme.LerNetDimens

/** Both graph renderers inherit the app's text colors, rather than canvas-library defaults. */
@Composable
internal fun SchemaNodeSurface(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable ColumnScope.() -> Unit,
) {
    val onCard = MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(
        LocalContentColor provides onCard,
        LocalTextStyle provides TextStyle(color = onCard),
    ) {
        Column(
            modifier.fillMaxWidth().background(containerColor).padding(LerNetDimens.contentPadding),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
            content = content,
        )
    }
}
