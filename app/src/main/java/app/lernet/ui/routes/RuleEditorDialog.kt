package app.lernet.ui.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun RuleEditorDialog(
    title: String,
    onDismiss: () -> Unit,
    errors: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight()) {
            Column(
                Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.headlineSmall)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content
                )
                errors()
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
        }
    }
}
