package app.lernet.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Complex forms use bounded regular measurement, without AlertDialog text-slot intrinsic sizing. */
@Composable
fun ExpertFormDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) { text() }
                confirmButton()
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { dismissButton() }
            }
        }
    }
}
