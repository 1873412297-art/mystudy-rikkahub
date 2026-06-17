package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.GroupContextOptions
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.GroupReplyOptions
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import kotlin.uuid.Uuid

class AssistantGroupMembersVM(
    private val assistantId: String,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val assistant: StateFlow<Assistant?> = settingsStore.settingsFlowRaw
        .map { settings -> settings.assistants.find { it.id.toString() == assistantId } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val allAssistants: StateFlow<List<Assistant>> = settingsStore.settingsFlowRaw
        .map { it.assistants }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun addMember(targetAssistant: Assistant) {
        val current = assistant.value ?: return
        val member = GroupMember(
            id = Uuid.random(),
            assistantId = targetAssistant.id,
            displayName = targetAssistant.name,
            avatar = targetAssistant.avatar,
        )
        updateAssistant(current.copy(groupMembers = current.groupMembers + member))
    }

    fun removeMember(memberId: Uuid) {
        val current = assistant.value ?: return
        updateAssistant(current.copy(groupMembers = current.groupMembers.filter { it.id != memberId }))
    }

    fun updateMember(member: GroupMember) {
        val current = assistant.value ?: return
        updateAssistant(current.copy(
            groupMembers = current.groupMembers.map { if (it.id == member.id) member else it }
        ))
    }

    fun setTurnTakingStrategy(strategy: TurnTakingStrategy) {
        val current = assistant.value ?: return
        updateAssistant(current.copy(turnTakingStrategy = strategy))
    }

    fun updateGroupReplyOptions(options: GroupReplyOptions) {
        val current = assistant.value ?: return
        updateAssistant(current.copy(groupReplyOptions = options))
    }

    fun updateGroupContextOptions(options: GroupContextOptions) {
        val current = assistant.value ?: return
        updateAssistant(current.copy(groupContextOptions = options))
    }

    fun updateMemberOrder(members: List<GroupMember>) {
        val current = assistant.value ?: return
        updateAssistant(current.copy(groupMembers = members))
    }

    private fun updateAssistant(updated: Assistant) {
        val id = updated.id
        scope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { if (it.id == id) updated else it }
                )
            }
        }
    }
}
