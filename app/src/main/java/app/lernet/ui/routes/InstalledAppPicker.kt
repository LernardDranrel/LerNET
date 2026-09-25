package app.lernet.ui.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val label: String, val packageName: String)

@Composable
internal fun AppSelectionField(values: List<String>, onChange: (List<String>) -> Unit) {
    var pickerOpen by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { pickerOpen = true }) {
        Text(stringResource(R.string.rule_pick_app))
    }
    if (pickerOpen) {
        InstalledAppDialog(values, onChange, onDismiss = { pickerOpen = false })
    }
}

@Composable
private fun InstalledAppDialog(values: List<String>, onChange: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            context.packageManager.getInstalledApplications(0).map { info ->
                InstalledApp(
                    info.loadLabel(context.packageManager).toString(),
                    info.packageName,
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rule_pick_app)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.rule_search_app)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val visible = apps?.filter {
                    query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
                if (visible == null) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            val selected = app.packageName in values
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onChange(if (selected) values - app.packageName else values + app.packageName)
                                }.padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = {
                                        onChange(if (selected) values - app.packageName else values + app.packageName)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
