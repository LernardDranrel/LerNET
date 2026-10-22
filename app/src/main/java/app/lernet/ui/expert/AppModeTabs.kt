package app.lernet.ui.expert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lernet.R

/**
 * Mode switch «VPN | Эксперт» (audit N2/N19, mockup m4): an M3-style segmented button that lives in the
 * title of the top bar on both modes, so it no longer takes its own 48 dp row. Same component as on Windows.
 */
@Composable
fun AppModeTabs(expert: Boolean, onVpn: () -> Unit, onExpert: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val description = stringResource(app.lernet.R.string.mode_switch)
    Row(
        modifier.clip(RoundedCornerShape(50))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(50))
            .padding(3.dp).selectableGroup().semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ModeSegment(stringResource(R.string.expert_tab_vpn), !expert, onVpn, Modifier.weight(1f))
        ModeSegment(stringResource(R.string.expert_tab_expert), expert, onExpert, Modifier.weight(1f))
    }
}

/** The same segmented button for any two or three choices (settings «Режим», events switch). */
@Composable
fun LerSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.clip(RoundedCornerShape(50)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(50))
            .padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            ModeSegment(label, index == selected, { onSelect(index) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
            .background(if (selected) scheme.secondaryContainer else scheme.background)
            .selectable(selected = selected, role = Role.Tab, onClick = { if (!selected) onClick() })
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
