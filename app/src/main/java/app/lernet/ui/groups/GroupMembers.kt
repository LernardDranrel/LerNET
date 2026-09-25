package app.lernet.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import app.lernet.R
import app.lernet.config.model.Group
import app.lernet.config.model.Profile
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableListItemScope

@Composable
internal fun GroupMembers(
    group: Group,
    profiles: List<Profile>,
    onIntent: (GroupsIntent) -> Unit,
) {
    val members = group.profileIds.mapNotNull { id -> profiles.firstOrNull { it.id == id } }
    val haptic = LocalHapticFeedback.current
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LerNetDimens.cardGap)) {
        if (members.isEmpty()) {
            Text(
                stringResource(R.string.group_empty_drop),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(min = LerNetDimens.buttonMinHeight),
            )
        }
        ReorderableColumn(
            list = members,
            onSettle = { from, to ->
                val target = members.getOrNull(from) ?: return@ReorderableColumn
                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                onIntent(GroupsIntent.MoveProfile(target.id, group.id, to))
            },
            onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.cardGap),
        ) { index, profile, _ ->
            key(profile.id) {
                ReorderableItem {
                    MemberRow(
                        profile = profile,
                        scope = this,
                        canUp = index > 0,
                        canDown = index < members.lastIndex,
                        moveUp = moveUp,
                        moveDown = moveDown,
                        onUp = { onIntent(GroupsIntent.MoveProfile(profile.id, group.id, index - 1)) },
                        onDown = { onIntent(GroupsIntent.MoveProfile(profile.id, group.id, index + 1)) },
                        onRemove = { onIntent(GroupsIntent.ToggleMember(group.id, profile.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    profile: Profile,
    scope: ReorderableListItemScope,
    canUp: Boolean,
    canDown: Boolean,
    moveUp: String,
    moveDown: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LerNetDimens.buttonMinHeight)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(moveUp) {
                        if (canUp) onUp()
                        canUp
                    },
                    CustomAccessibilityAction(moveDown) {
                        if (canDown) onDown()
                        canDown
                    },
                )
            },
    ) {
        IconButton(
            onClick = {},
            modifier = with(scope) {
                Modifier.draggableHandle(
                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                    onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                )
            },
        ) {
            Icon(LerNetSymbols.drag(), contentDescription = stringResource(R.string.group_grip))
        }
        Text(profile.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onRemove, modifier = Modifier.lernetButton()) {
            Text(stringResource(R.string.group_remove_member))
        }
    }
}
