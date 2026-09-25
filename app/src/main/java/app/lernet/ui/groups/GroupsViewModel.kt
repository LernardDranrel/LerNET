package app.lernet.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lernet.config.model.Group
import app.lernet.config.model.GroupLayout
import app.lernet.config.model.Profile
import app.lernet.config.repo.ConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupsUiState(
    val groups: List<Group> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val draftName: String = "",
)

sealed class GroupsIntent {
    data class SetDraftName(val name: String) : GroupsIntent()

    data object Create : GroupsIntent()

    data class Rename(val id: String, val name: String) : GroupsIntent()

    data class Delete(val id: String) : GroupsIntent()

    data class ToggleMember(val groupId: String, val profileId: String) : GroupsIntent()

    data class MoveProfile(val profileId: String, val toGroupId: String, val toIndex: Int) : GroupsIntent()

    data class MoveGroup(val groupId: String, val toIndex: Int) : GroupsIntent()
}

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val repository: ConfigRepository,
) : ViewModel() {
    private val draftName = MutableStateFlow("")

    val state: StateFlow<GroupsUiState> = combine(
        repository.groups,
        repository.profiles,
        draftName,
    ) { groups, profiles, name ->
        GroupsUiState(groups, profiles, name)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState())

    fun onIntent(intent: GroupsIntent) {
        viewModelScope.launch {
            when (intent) {
                is GroupsIntent.SetDraftName -> draftName.update { intent.name }
                GroupsIntent.Create -> {
                    val name = draftName.value.trim()
                    if (name.isNotEmpty()) {
                        repository.upsertGroup(name)
                        draftName.value = ""
                    }
                }
                is GroupsIntent.Rename -> repository.renameGroup(intent.id, intent.name)
                is GroupsIntent.Delete -> repository.deleteGroup(intent.id)
                is GroupsIntent.ToggleMember -> toggle(intent)
                is GroupsIntent.MoveProfile -> {
                    val next = GroupLayout.moveProfile(
                        state.value.groups,
                        intent.profileId,
                        intent.toGroupId,
                        intent.toIndex,
                    )
                    repository.replaceAllMemberships(next)
                }
                is GroupsIntent.MoveGroup -> {
                    val next = GroupLayout.moveGroup(state.value.groups, intent.groupId, intent.toIndex)
                    repository.reorderGroups(next.map { it.id })
                }
            }
        }
    }

    private suspend fun toggle(intent: GroupsIntent.ToggleMember) {
        val groups = state.value.groups
        val group = groups.firstOrNull { it.id == intent.groupId } ?: return
        val next = if (intent.profileId in group.profileIds) {
            groups.map { item ->
                if (item.id == group.id) item.copy(profileIds = item.profileIds - intent.profileId) else item
            }
        } else {
            GroupLayout.moveProfile(groups, intent.profileId, group.id, group.profileIds.size)
        }
        repository.replaceAllMemberships(next)
    }
}
