package app.lernet.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.lernet.BuildConfig
import app.lernet.config.update.UpdateMonitor
import app.lernet.ui.components.PanelCard
import kotlinx.coroutines.launch

private object AndroidUpdates {
    private var instance: UpdateMonitor? = null
    @Synchronized fun monitor(context: Context): UpdateMonitor {
        return instance ?: run {
            val prefs = context.applicationContext.getSharedPreferences("client-updates", Context.MODE_PRIVATE)
            UpdateMonitor(BuildConfig.VERSION_NAME,
                { prefs.getBoolean("automatic", true) }, { prefs.edit().putBoolean("automatic", it).apply() },
                { prefs.getLong("lastCheck", 0) }, { prefs.edit().putLong("lastCheck", it).apply() })
        }.also { instance = it }
    }
}

@Composable
fun AndroidUpdatePrompt() {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val monitor = remember { AndroidUpdates.monitor(context) }
    val state by monitor.state.collectAsState()
    var dismissed by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { monitor.check() }
    state.release?.takeIf { it.version != dismissed }?.let { release ->
        AlertDialog(onDismissRequest = { dismissed = release.version },
            title = { Text("Доступен LerNET v${release.version}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Откроется релиз на GitHub. Скачайте APK и подтвердите обновление в Android. Профили сохранятся.")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                runCatching { uri.openUri(release.pageUrl) }.onSuccess { dismissed = release.version }
                    .onFailure { error = "Не удалось открыть браузер" }
            }) { Text("Открыть релиз") } },
            dismissButton = { TextButton(onClick = { dismissed = release.version }) { Text("Позже") } })
    }
}

@Composable
fun UpdatesCard() {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val monitor = remember { AndroidUpdates.monitor(context) }
    val state by monitor.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    PanelCard {
        Text("Обновления LerNET", style = MaterialTheme.typography.titleMedium)
        Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(state.automatic, onCheckedChange = monitor::automatic)
            Text("Проверять при запуске, не чаще раза в сутки", modifier = Modifier.weight(1f))
        }
        Text("Запрос идёт на GitHub. Профили и данные диагностики не отправляются.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { scope.launch { monitor.check(manual = true) } }, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) { Text("Проверить обновления") }
        state.release?.let { release ->
            Button(onClick = { runCatching { uri.openUri(release.pageUrl) }.onFailure { error = "Не удалось открыть браузер" } }, modifier = Modifier.fillMaxWidth()) { Text("Открыть релиз v${release.version} и скачать APK") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
