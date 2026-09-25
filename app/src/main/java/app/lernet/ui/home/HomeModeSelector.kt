package app.lernet.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.engine.RunMode
import app.lernet.ui.motion.motionTween
import app.lernet.ui.motion.rememberReduceMotion

/** The selected mode is legible at a glance, including without color perception. */
@Composable
internal fun HomeModeSelector(mode: RunMode, onSelect: (RunMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.mode_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RunMode.FULL_VPN, RunMode.PROXY).forEach { item ->
                val selected = item == mode
                val scheme = MaterialTheme.colorScheme
                val background by animateColorAsState(
                    if (selected) scheme.primaryContainer else scheme.surfaceContainer,
                    animationSpec = motionTween(rememberReduceMotion(), 180), label = "mode-background",
                )
                Surface(
                    selected = selected,
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    shape = MaterialTheme.shapes.large,
                    color = background,
                    contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
                ) {
                    Column(
                        Modifier.heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(if (item == RunMode.FULL_VPN) R.string.mode_vpn else R.string.mode_proxy),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(
                                if (selected) R.string.home_mode_selected
                                else if (item == RunMode.FULL_VPN) R.string.home_mode_whole_device
                                else R.string.home_mode_app_only,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        Text(
            stringResource(if (mode == RunMode.FULL_VPN) R.string.mode_vpn_help else R.string.mode_proxy_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
