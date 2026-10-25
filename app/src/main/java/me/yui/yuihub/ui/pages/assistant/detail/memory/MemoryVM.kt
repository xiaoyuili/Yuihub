package me.yui.yuihub.ui.pages.assistant.detail.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.yui.yuihub.data.ai.memory.MemoryConsolidator
import me.yui.yuihub.data.ai.memory.MemoryExtractor
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.getAssistantById
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.AssistantMemory
import me.yui.yuihub.data.model.MemoryEvent
import me.yui.yuihub.data.model.MemoryStats
import me.yui.yuihub.data.repository.ConversationRepository
import me.yui.yuihub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

class MemoryVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val consolidator: MemoryConsolidator,
    private val extractor: MemoryExtractor,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val assistantId = runCatching { Uuid.parse(id) }.getOrNull()

    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = settingsStore.settingsFlow.value,
    )

    val assistant: StateFlow<Assistant> = settingsStore.settingsFlow
        .map { latest -> assistantId?.let { latest.getAssistantById(it) } ?: Assistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Assistant())

    val memories: StateFlow<List<AssistantMemory>> = memoryRepository.getMemoriesFlow(id)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val events: StateFlow<List<MemoryEvent>> = memoryRepository.eventsFlow(id, 40)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val stats: StateFlow<MemoryStats> = settings
        .flatMapLatest { latest ->
            memoryRepository.statsFlow(
                assistantId = id,
                coreBudget = latest.memoryCoreCharBudget,
                growthThreshold = latest.memoryOrganizeGrowth,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MemoryStats())

    private val _organize = MutableStateFlow<OrganizeUiState>(OrganizeUiState.Idle)
    val organize: StateFlow<OrganizeUiState> = _organize.asStateFlow()

    private val _extracting = MutableStateFlow(false)
    val extracting: StateFlow<Boolean> = _extracting.asStateFlow()

    fun updateAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val latest = settingsStore.settingsFlow.value
            settingsStore.update(
                latest.copy(
                    assistants = latest.assistants.map {
                        if (it.id == assistant.id) assistant else it
                    }
                )
            )
        }
    }

    fun updateSettings(transform: (me.yui.yuihub.data.datastore.Settings) -> me.yui.yuihub.data.datastore.Settings) {
        viewModelScope.launch {
            settingsStore.update(transform(settingsStore.settingsFlow.value))
        }
    }

    fun createMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.createMemory(
                assistantId = id,
                content = memory.content,
                title = memory.title,
                kind = memory.kind,
                section = memory.section,
                category = memory.category,
                keywords = memory.keywords,
                weight = memory.weight,
                status = memory.status,
                scope = memory.scope,
                workspaceId = assistant.value.workspaceId?.toString(),
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val workspaceId = assistant.value.workspaceId?.toString()
            memoryRepository.updateMemory(
                memory.copy(
                    workspaceId = if (memory.scope == me.yui.yuihub.data.model.MemoryScope.WORKSPACE) {
                        memory.workspaceId ?: workspaceId
                    } else null,
                )
            )
        }
    }

    fun archiveMemories(ids: List<Int>) {
        viewModelScope.launch { memoryRepository.archiveMemory(ids) }
    }

    fun restoreMemories(ids: List<Int>) {
        viewModelScope.launch { memoryRepository.restoreMemory(ids) }
    }

    fun deleteMemories(ids: List<Int>) {
        viewModelScope.launch { ids.forEach { memoryRepository.deleteMemory(it) } }
    }

    fun promoteToCore(id: Int, section: String) {
        viewModelScope.launch { memoryRepository.promoteToCore(id, section) }
    }

    fun demoteToFact(id: Int) {
        viewModelScope.launch { memoryRepository.demoteToFact(id) }
    }

    fun mergeMemories(ids: List<Int>, content: String, title: String = "") {
        viewModelScope.launch { memoryRepository.mergeMemories(ids, content, title) }
    }

    fun organizeNow() {
        if (_organize.value is OrganizeUiState.Running) return
        viewModelScope.launch {
            _organize.value = OrganizeUiState.Running(MemoryConsolidator.OrganizeProgress.Stage.MERGE, 0, 1)
            val result = runCatching {
                consolidator.organize(
                    assistantId = id,
                    settings = settingsStore.settingsFlow.value,
                    coreBudget = settingsStore.settingsFlow.value.memoryCoreCharBudget,
                ) { progress ->
                    _organize.value = OrganizeUiState.Running(progress.stage, progress.index, progress.total)
                }
            }.getOrNull()
            _organize.value = if (result == null) {
                OrganizeUiState.Failed
            } else {
                OrganizeUiState.Done(result)
            }
        }
    }

    fun extractNow() {
        if (_extracting.value) return
        val assistantKey = assistantId ?: return
        viewModelScope.launch {
            val conversation = runCatching {
                conversationRepository.getRecentConversations(assistantKey, 1).firstOrNull()
            }.getOrNull() ?: return@launch
            _extracting.value = true
            extractor.launchExtraction(conversation.id) {
                _extracting.value = false
            }
        }
    }

    fun exportMarkdown(onReady: (String) -> Unit) {
        viewModelScope.launch {
            onReady(memoryRepository.exportMarkdown(id))
        }
    }

    fun clearArchived() {
        viewModelScope.launch { memoryRepository.deleteArchived(id) }
    }

    fun clearAll() {
        viewModelScope.launch { memoryRepository.deleteMemoriesOfAssistant(id) }
    }
}

sealed interface OrganizeUiState {
    data object Idle : OrganizeUiState
    data class Running(
        val stage: MemoryConsolidator.OrganizeProgress.Stage,
        val index: Int,
        val total: Int,
    ) : OrganizeUiState
    data class Done(val result: MemoryConsolidator.ConsolidationResult) : OrganizeUiState
    data object Failed : OrganizeUiState
}
