package app.lernet.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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

/** Presentation only: selecting a workspace never starts or stops the network. */
@Composable
internal fun DesktopModeTabs(
    expert: Boolean,
    onSelect: (Boolean) -> Unit,
    status: String = "",
    reducedMotion: Boolean = false,
) {
    Box(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(false to "VPN", true to "Экспертный режим").forEach { (mode, title) ->
                val selected = mode == expert
                val fill by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                    tween(if (reducedMotion) 0 else 160),
                )
                Surface(
                    color = fill,
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 4.dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = .45f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                ) {
                    Box(
                        Modifier.width(180.dp).height(42.dp).selectable(selected, role = Role.Tab, onClick = { onSelect(mode) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
        if (status.isNotBlank()) {
            Text(
                status, Modifier.align(Alignment.CenterEnd).widthIn(max = 180.dp),
                color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun DesktopWorkspaceSidebar(onOpenNetwork: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.width(196.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("L", color = androidx.compose.ui.graphics.Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("LerNET", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("для Windows", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(35.dp))
        content()
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onOpenNetwork, modifier = Modifier.fillMaxWidth()) { Text("Сеть устройства ↗", fontSize = 12.sp) }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text("LerNET v$APP_VERSION", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
internal fun WorkspaceNavigationItem(title: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        icon()
        Text(
            title, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1
        )
    }
    Spacer(Modifier.height(4.dp))
}
