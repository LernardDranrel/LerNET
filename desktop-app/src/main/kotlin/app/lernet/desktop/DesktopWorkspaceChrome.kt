package app.lernet.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Global status shown in the window strip on every page (G1): the same words on both platforms. */
internal data class WorkspaceStatus(val text: String, val tone: StatusTone = StatusTone.NEUTRAL)

internal enum class StatusTone { NEUTRAL, OK, PROGRESS, PROBLEM, HOLD }

/** Presentation only: selecting a workspace never starts or stops the network. */
@Composable
internal fun DesktopModeTabs(
    expert: Boolean,
    onSelect: (Boolean) -> Unit,
    status: WorkspaceStatus? = null,
    reducedMotion: Boolean = false,
    onStatusClick: (() -> Unit)? = null,
) {
    // N19: one segmented button (M3 / Fluent), the same component as on the phone.
    Box(Modifier.fillMaxWidth().height(if (expert) 46.dp else 52.dp).padding(horizontal = if (expert) 8.dp else 24.dp),
        contentAlignment = if (expert) Alignment.CenterStart else Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.selectableGroup().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(false to "VPN", true to "Эксперт").forEach { (mode, title) ->
                    val selected = mode == expert
                    val fill by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        tween(if (reducedMotion) 0 else 160),
                    )
                    Box(
                        Modifier.width(if (expert) 72.dp else 140.dp).height(if (expert) 29.dp else 34.dp)
                            .clip(RoundedCornerShape(8.dp)).background(fill)
                            .selectable(selected, role = Role.Tab, onClick = { onSelect(mode) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (status != null && status.text.isNotBlank()) {
            val color = when (status.tone) {
                StatusTone.OK -> MaterialTheme.colorScheme.secondary
                StatusTone.PROGRESS -> androidx.compose.ui.graphics.Color(0xFFFFD27B)
                StatusTone.PROBLEM -> MaterialTheme.colorScheme.error
                StatusTone.HOLD -> androidx.compose.ui.graphics.Color(0xFFF1CC80)
                StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(Modifier.fillMaxWidth().padding(start = if (expert) 160.dp else 0.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                "● ${status.text}",
                Modifier.widthIn(max = 300.dp)
                    .then(if (onStatusClick != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onStatusClick) else Modifier)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            }
        }
    }
}

@Composable
internal fun DesktopWorkspaceSidebar(
    onOpenNetwork: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 196.dp,
    title: String = "LerNET",
    expertBrand: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        // Flush to the window top beside VPN|Эксперт (that strip lives only in the right column).
        Modifier.width(width).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).background(if (expertBrand) app.lernet.desktop.expert.ExpertColors.blue else androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("L", color = if (expertBrand) app.lernet.desktop.expert.ExpertColors.background else androidx.compose.ui.graphics.Color.White,
                    fontSize = 19.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("для Windows", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f)) { content() }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenNetwork, modifier = Modifier.fillMaxWidth()) { Text("Анализ сети ↗", fontSize = 12.sp) }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text("LerNET v$APP_VERSION", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
internal fun WorkspaceNavigationItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    icon: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        icon()
        Text(
            title, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            Spacer(Modifier.weight(1f))
            Text(badge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun WorkspaceNavigationHeader(title: String) {
    Text(
        title.uppercase(), Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
    )
}
