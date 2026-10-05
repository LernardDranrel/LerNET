package app.lernet.ui.expert

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lernet.R
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.ExpertExitKey
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import java.text.DateFormat
import java.util.Date

@Composable
internal fun scopeTitle(scope: PolicyScope, bundle: TransferBundle): String = when (scope) {
    PolicyScope.Device -> stringResource(R.string.expert_device_scope)
    is PolicyScope.Profile -> stringResource(
        R.string.expert_profile_scope,
        bundle.profiles.firstOrNull { it.id == scope.id }?.name ?: scope.id
    )
    is PolicyScope.Folder -> stringResource(R.string.expert_folder_scope, bundle.groups.firstOrNull { it.id == scope.id }?.name ?: scope.id)
}

@Composable
internal fun targetTitle(target: PolicyTarget, bundle: TransferBundle, policy: NetworkPolicy): String = when (target) {
    PolicyTarget.Direct -> stringResource(R.string.expert_direct)
    PolicyTarget.Block -> stringResource(R.string.expert_block)
    PolicyTarget.CurrentExit -> stringResource(R.string.expert_current_exit)
    is PolicyTarget.Profile -> scopeTitle(PolicyScope.Profile(target.id), bundle)
    is PolicyTarget.Folder -> scopeTitle(PolicyScope.Folder(target.id), bundle)
    is PolicyTarget.Channel -> stringResource(
        R.string.expert_channel,
        policy.channels.firstOrNull {
            it.id == target.id
        }?.name ?: target.id
    )
}

internal fun ExpertSessionPhase.titleResource(): Int = when (this) {
    ExpertSessionPhase.STOPPED -> R.string.expert_stopped
    ExpertSessionPhase.STARTING -> R.string.expert_starting
    ExpertSessionPhase.RUNNING -> R.string.expert_running
    ExpertSessionPhase.STOPPING -> R.string.expert_stopping
    ExpertSessionPhase.FAILED -> R.string.expert_failed
}

internal fun ExitPhase.titleResource(): Int = when (this) {
    ExitPhase.SLEEPING -> R.string.expert_exit_sleeping
    ExitPhase.STARTING -> R.string.expert_exit_starting
    ExitPhase.READY -> R.string.expert_exit_ready
    ExitPhase.DEGRADED -> R.string.expert_exit_degraded
    ExitPhase.FAILED -> R.string.expert_exit_failed
    ExitPhase.DRAINING -> R.string.expert_exit_draining
}

@Composable
internal fun idleDurationTitle(milliseconds: Long): String =
    if (milliseconds % 60_000L == 0L) {
        stringResource(R.string.expert_duration_minutes, milliseconds / 60_000)
    } else {
        stringResource(R.string.expert_duration_seconds, expertSecondsInput(milliseconds).replace('.', ','))
    }

@Composable
internal fun exitTitle(key: ExpertExitKey, bundle: TransferBundle, policy: NetworkPolicy?): String {
    val channel = key.channelId?.let { id -> policy?.channels?.firstOrNull { it.id == id }?.name ?: id }
    val folder = key.folderId?.let { id -> bundle.groups.firstOrNull { it.id == id }?.name ?: id }
    val profile = bundle.profiles.firstOrNull { it.id == key.profileId }?.name ?: key.profileId
    return listOfNotNull(channel, folder, profile).joinToString(" · ")
}

internal fun exitIdentity(key: ExpertExitKey): String =
    listOfNotNull(key.channelId, key.folderId, key.profileId).joinToString(" · ")

@Composable
internal fun observationTimeTitle(atMs: Long?): String =
    atMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)) }
        ?: stringResource(R.string.expert_value_unknown)

@Composable
internal fun connectionStateTitle(active: Boolean?): String = stringResource(
    when (active) {
        true -> R.string.expert_connection_active
        false -> R.string.expert_connection_closed
        null -> R.string.expert_connection_unknown
    },
)
