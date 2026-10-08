package app.lernet.ui.expert

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lernet.R

@Composable
fun AppModeTabs(expert: Boolean, onVpn: () -> Unit, onExpert: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .drawBehind { drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FolderTab(stringResource(R.string.expert_tab_vpn), !expert, onVpn, Modifier.weight(1f))
            FolderTab(stringResource(R.string.expert_tab_expert), expert, onExpert, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FolderTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape)
            .background(if (selected) scheme.surfaceVariant else scheme.background)
            .drawBehind {
                if (selected) {
                    val radius = 14.dp.toPx()
                    val edge = 0.5.dp.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(edge, size.height)
                            lineTo(edge, radius)
                            quadraticBezierTo(edge, edge, radius, edge)
                            lineTo(size.width - radius, edge)
                            quadraticBezierTo(size.width - edge, edge, size.width - edge, radius)
                            lineTo(size.width - edge, size.height)
                        },
                        scheme.outline, style = Stroke(1.dp.toPx()),
                    )
                }
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
