package me.yui.yuihub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.yui.yuihub.data.db.dao.ConversationDAO
import me.yui.yuihub.data.db.dao.MessageNodeDAO
import me.yui.yuihub.data.db.dao.getConversationUsageStats
import me.yui.yuihub.data.db.dao.getTokenStats
import me.yui.yuihub.data.db.dao.getTokenUsagePerDay
import me.yui.yuihub.data.datastore.SettingsStore
import java.time.LocalDate

data class AppStats(
    val isLoading: Boolean = true,
    val conversationTitle: String = "",
    val currentPromptTokens: Long = 0L,
    val currentCompletionTokens: Long = 0L,
    val currentCachedTokens: Long = 0L,
    val currentMessageCount: Int = 0,
    val currentModelCalls: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val totalConversations: Int = 0,
    val totalModelCalls: Int = 0,
    val tokensPerDay: Map<LocalDate, Long> = emptyMap(),
    val launchCount: Int = 0,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
    private val conversationId: String?,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()
        val startDate = today.minusDays(29).toString()

        val tokensPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getTokenUsagePerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.tokens }.getOrNull()
                }
                .toMap()
        }

        val tokenStats = messageNodeDAO.getTokenStats()
        val totalConversations = conversationDAO.countAll()

        val currentStats = conversationId?.let { messageNodeDAO.getConversationUsageStats(it) }
        val conversationTitle = conversationId?.let { conversationDAO.getConversationById(it)?.title }.orEmpty()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        _stats.value = AppStats(
            isLoading = false,
            conversationTitle = conversationTitle,
            currentPromptTokens = currentStats?.promptTokens ?: 0L,
            currentCompletionTokens = currentStats?.completionTokens ?: 0L,
            currentCachedTokens = currentStats?.cachedTokens ?: 0L,
            currentMessageCount = currentStats?.totalMessages ?: 0,
            currentModelCalls = currentStats?.assistantMessages ?: 0,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            totalConversations = totalConversations,
            totalModelCalls = tokenStats.assistantMessages,
            tokensPerDay = tokensPerDay,
            launchCount = launchCount,
        )
    }
}
