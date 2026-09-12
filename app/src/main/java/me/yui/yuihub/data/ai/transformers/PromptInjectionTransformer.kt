package me.yui.yuihub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.model.InjectionPosition
import me.yui.yuihub.data.model.PromptInjection
import me.yui.yuihub.data.model.Lorebook
import me.yui.yuihub.data.ai.lorebook.LorebookEngine
import kotlin.uuid.Uuid

/**
 * 提示词注入转换器
 *
 * 根据 Assistant 关联的 ModeInjection 和 Lorebook 进行提示词注入
 */
object PromptInjectionTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return transformMessages(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds,
        )
    }
}

/**
 * 核心注入逻辑（可测试的纯函数）
 *
 * ## 缓存稳定性设计（分段追加模型）
 *
 * 请求前缀缓存命中的前提是「同一激活集合拼出完全相同的字节」。为此：
 * 1. **只追加，不重写**：任何注入都不会修改既有消息的字节。模式注入作为独立消息
 *    插在 system 之后（新开一段：system 段缓存保留，模式段重建）；世界书触发条目
 *    统一作为当前轮次尾部补充段（变化只影响尾部，system+早期历史前缀始终命中）。
 * 2. **匹配上下文跨步稳定**：排除合成消息、记忆快照，以及**仍在生成中的助手消息**
 *    （finishedAt == null）——否则工具循环里助手回复边流边扫，条目集合会在同一轮内反复变化。
 * 3. **确定性随机**：种子由「助手 id + 用户消息数」派生，同一轮内所有 step 得到同一结果，
 *    仅在新用户消息时重新掷；不会每步重掷导致前缀每步失效。
 * 4. **稳定排序**：激活结果按书籍内条目顺序排序，相同集合永远渲染相同内容。
 * 5. 未命中任何条目时直接原样返回，零改动。
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<UIMessage> {
    // 收集所有需要注入的内容
    val injections = collectInjections(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
    )

    if (injections.isEmpty()) {
        return messages
    }

    // 拆分流：模式注入按声明位置；世界书条目一律归入尾部补充段（不重排历史）
    val (modeOnly, lorebookOnly) = injections.partition { it is PromptInjection.ModeInjection }

    var result = messages
    if (modeOnly.isNotEmpty()) {
        val byPosition = modeOnly
            .sortedByDescending { it.priority }
            .groupBy { it.position }
        result = applyModeInjections(result, byPosition)
    }
    if (lorebookOnly.isNotEmpty()) {
        val byPriority = lorebookOnly.sortedByDescending { it.priority }
        result = applyLorebookTailSegment(result, byPriority)
    }
    return result
}

/**
 * 收集需要注入的内容
 */
internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<PromptInjection> {
    val injections = mutableListOf<PromptInjection>()
    val effectiveModeInjectionIds = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val effectiveLorebookIds = if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        assistant.lorebookIds
    }

    // 1. 获取关联的 ModeInjection
    modeInjections
        .filter { it.enabled && effectiveModeInjectionIds.contains(it.id) }
        .forEach { injections.add(it) }

    // 2. 获取关联的 Lorebook 中被触发的 RegexInjection
    val enabledLorebooks = lorebooks.filter {
        it.enabled && effectiveLorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isNotEmpty()) {
        // 匹配上下文：排除 SYSTEM / 合成消息（记忆快照、时间提醒、注入自身），
        // 并排除仍在生成中的助手消息（finishedAt == null）——
        // 否则多步工具循环里助手回复边流边扫，条目集合会在同一轮内反复变化，破坏缓存。
        val matchableMessages = messages.filter { message ->
            message.role != MessageRole.SYSTEM &&
                !message.isSynthetic &&
                !(message.role == MessageRole.ASSISTANT && message.finishedAt == null)
        }
        // 确定性种子：同一轮内跨 step 稳定，仅在新用户消息时变化
        val userTurns = messages.count { it.role == MessageRole.USER }
        val seed = assistant.id.hashCode().toLong() * 1_000_003L + userTurns

        enabledLorebooks.forEach { lorebook ->
            injections.addAll(
                LorebookEngine.resolveEntries(
                    lorebook = lorebook,
                    messages = matchableMessages,
                    assistantPrompt = assistant.systemPrompt,
                    seed = seed,
                )
            )
        }
    }

    return injections
}

/**
 * 应用模式注入（分段追加模型）
 *
 * - BEFORE/AFTER_SYSTEM_PROMPT、TOP_OF_CHAT → 作为独立消息插入在 system 之后（新开一段，
 *   不重写 system 字节）：模式切换时 system 段缓存保留，仅模式段及其后重建。
 * - BOTTOM_OF_CHAT → 尾部补充段（最后一条消息之前）。
 * - AT_DEPTH → 保持原语义（从最新消息往前数），位置随轮次后移，尾部断裂已知。
 */
internal fun applyModeInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>
): List<UIMessage> {
    val result = messages.toMutableList()

    // 头部段：BEFORE/AFTER/TOP 合并为 system 后的独立段，按 position 语义排序
    val headInjections = listOf(
        InjectionPosition.BEFORE_SYSTEM_PROMPT,
        InjectionPosition.AFTER_SYSTEM_PROMPT,
        InjectionPosition.TOP_OF_CHAT,
    ).flatMap { byPosition[it].orEmpty() }
    if (headInjections.isNotEmpty()) {
        // 找到系统消息的索引（通常是第一条）；不存在时插在最前
        val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }
        var insertIndex = if (systemIndex >= 0) systemIndex + 1 else 0
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(headInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 尾部：BOTTOM_OF_CHAT 在最后一条消息之前插入
    val bottomInjections = byPosition[InjectionPosition.BOTTOM_OF_CHAT]
    if (!bottomInjections.isNullOrEmpty()) {
        var insertIndex = (result.size - 1).coerceAtLeast(0)
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(bottomInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // AT_DEPTH：在指定深度位置插入（从最新消息往前数）
    // 按 injectDepth 分组，相同深度的合并，按深度从大到小处理（避免索引变化问题）
    val atDepthInjections = byPosition[InjectionPosition.AT_DEPTH]
    if (!atDepthInjections.isNullOrEmpty()) {
        val byDepth = atDepthInjections.groupBy { it.injectDepth }
        byDepth.keys.sortedDescending().forEach { depth ->
            val injections = byDepth[depth] ?: return@forEach
            // 计算插入位置：result.size - depth，但要确保在有效范围内
            // depth=1 表示在最后一条消息之前，depth=2 表示在倒数第二条之前...
            var insertIndex = (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
            insertIndex = findSafeInsertIndex(result, insertIndex)
            createMergedInjectionMessages(injections).forEach { message ->
                result.add(insertIndex, message)
                insertIndex++
            }
        }
    }

    return result
}

/**
 * 世界书触发条目 → 当前轮次尾部补充段（分段追加模型）
 *
 * 不再按 position 重排历史（旧实现 BEFORE/AFTER_SYSTEM 会重写 system 消息、TOP_OF_CHAT
 * 插入点随截断漂移）：所有激活条目统一追加在最新用户消息之前，变化只影响尾部，
 * system+早期历史的前缀字节始终命中。position 字段保留用于导入导出兼容，运行时不区分。
 */
internal fun applyLorebookTailSegment(
    messages: List<UIMessage>,
    entries: List<PromptInjection>,
): List<UIMessage> {
    val result = messages.toMutableList()
    var insertIndex = (result.size - 1).coerceAtLeast(0)
    insertIndex = findSafeInsertIndex(result, insertIndex)
    createMergedInjectionMessages(entries).forEach { message ->
        result.add(insertIndex, message)
        insertIndex++
    }
    return result
}

/**
 * 将同一 role 的注入合并成消息列表
 * 按 role 分组后合并内容，返回合并后的消息列表
 */
private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> {
    return injections
        .groupBy { it.role }
        .map { (role, grouped) ->
            val mergedContent = grouped.joinToString("\n") { it.content }
            when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
                else -> UIMessage.user(mergedContent)
            }.copy(
                isSynthetic = true,
            )
        }
}

/**
 * 查找安全的插入位置，避免注入到 USER → ASSISTANT(含Tool) 之间
 *
 * 某些供应商（如 deepseek）要求 USER 之后紧跟带工具的 ASSISTANT，
 * 在两者之间插入消息会导致报错或破坏推理连续性。
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    // 向前查找，直到找到一个安全的位置
    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        // 不能插入到 USER → ASSISTANT(含Tool) 之间
        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
