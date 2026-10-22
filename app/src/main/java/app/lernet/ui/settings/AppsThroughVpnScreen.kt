package app.lernet.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.routes.InstalledApplicationIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DeviceApp(val label: String, val packageName: String, val system: Boolean)

/** Pure filter for the list (tested): search by name or package, system apps only on request. */
internal fun visibleApps(apps: List<DeviceApp>, query: String, showSystem: Boolean, bypass: Set<String>): List<DeviceApp> =
    apps.filter { app ->
        (showSystem || !app.system || app.packageName in bypass) &&
            (query.isBlank() || app.label.contains(query.trim(), true) || app.packageName.contains(query.trim(), true))
    }

/**
 * Audit R3: «Приложения через VPN» in three taps instead of building a rule. A checked app goes through
 * the VPN; an unchecked one is taken out of the VPN by Android itself (`exclude_package`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsThroughVpnScreen(bypass: Set<String>, onChange: (Set<String>) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<DeviceApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var changed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0).filter { it.packageName != context.packageName }.map { info ->
                DeviceApp(info.loadLabel(pm).toString(), info.packageName,
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pm.getLaunchIntentForPackage(info.packageName) == null)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }
    }
    fun set(next: Set<String>) {
        changed = true
        onChange(next)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apps_title)) },
                navigationIcon = {
                    IconButton(onBack) { Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(stringResource(R.string.apps_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (changed) item {
                Text(stringResource(R.string.apps_reconnect), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    placeholder = { Text(stringResource(R.string.apps_search)) })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(showSystem, { showSystem = !showSystem }, label = { Text(stringResource(R.string.apps_system)) })
                    if (bypass.isNotEmpty()) TextButton({ set(emptySet()) }, Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.apps_all_on))
                    }
                }
            }
            val list = apps
            if (list == null) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            else items(visibleApps(list, query, showSystem, bypass), key = { it.packageName }) { app ->
                val through = app.packageName !in bypass
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(role = Role.Checkbox) { set(if (through) bypass + app.packageName else bypass - app.packageName) }
                        .testTag("app_${app.packageName}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InstalledApplicationIcon(app.packageName)
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(through, onCheckedChange = null)
                }
            }
        }
    }
}
