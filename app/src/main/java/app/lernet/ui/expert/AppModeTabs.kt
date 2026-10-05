package app.lernet.ui.expert

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lernet.R

@Composable
fun AppModeTabs(expert: Boolean, onVpn: () -> Unit, onExpert: () -> Unit) {
    TabRow(selectedTabIndex = if (expert) 1 else 0) {
        Tab(selected = !expert, onClick = onVpn, text = { Text(stringResource(R.string.expert_tab_vpn)) })
        Tab(selected = expert, onClick = onExpert, text = { Text(stringResource(R.string.expert_tab_expert)) })
    }
}
