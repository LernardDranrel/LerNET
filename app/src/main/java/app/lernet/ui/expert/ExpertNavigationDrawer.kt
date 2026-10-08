package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.ui.components.LerNetLogo
import app.lernet.ui.theme.LerNetDimens

@Composable
internal fun ExpertNavigationDrawer(
    selected: ExpertSection, onSection: (ExpertSection) -> Unit,
    onProfiles: () -> Unit, onSettings: () -> Unit,
    onDiagnostics: () -> Unit, onNetwork: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(LerNetDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LerNetLogo(size = 36.dp)
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.expert_tab_expert), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            ExpertSection.entries.forEach { section ->
                NavigationDrawerItem(selected = section == selected, onClick = { onSection(section) },
                    label = { Text(stringResource(section.label)) })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.expert_app_tools), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            listOf(
                R.string.expert_profiles_folders to onProfiles,
                R.string.expert_app_settings to onSettings,
                R.string.diag_title to onDiagnostics,
                R.string.expert_device_network to onNetwork,
            ).forEach { (label, action) ->
                NavigationDrawerItem(selected = false, onClick = action,
                    label = { Text(stringResource(label)) })
            }
        }
    }
}
