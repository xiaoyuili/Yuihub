package me.yui.yuihub.data.ai.lorebook

import me.rerere.ai.ui.UIMessage
import me.yui.yuihub.data.model.Lorebook
import me.yui.yuihub.data.model.PromptInjection
import me.yui.yuihub.data.model.isTriggered
import me.yui.yuihub.data.model.matchScore
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * 世界书匹配引擎。
 *
 * 对齐 SillyTavern World Info / Character Card V2-V3 的激活语义：
 * 触发匹配（主键 + 次级键选择性逻辑）→ 时序效果（delay / cooldown / sticky）→
 * 递归扫描 → 分组竞争 → 概率 → token 预算。
 *
 * 时序效果不持久化状态：sticky/cooldown 由「最近 N 条消息的扫描窗口」重放推导，
 * 因此跨重启、跨会话都稳定，也不需要数据库迁移。
 */
object LorebookEngine {

    /**
     * 解析一本书中当前应激活的条目。
     *
     * @param messages 用于匹配的历史消息（不含 system / 合成消息 / 正在生成中的助手消息），按时间从旧到新
     * @param assistantPrompt 书开启 scanAssistantPrompt 时，额外并入匹配文本的助手提示词
     * @param seed 随机源种子。必须由「同一轮对话内稳定、新用户消息才变化」的值派生，
     *   否则概率/分组会每步重掷，导致请求前缀每步都变、缓存失效。
     */
    fun resolveEntries(
        lorebook: Lorebook,
        messages: List<UIMessage>,
        assistantPrompt: String? = null,
        seed: Long = 0L,
    ): List<PromptInjection.RegexInjection> {
        if (!lorebook.enabled) return emptyList()
        val entries = lorebook.entries
        if (entries.isEmpty()) return emptyList()

        // 确定性随机：同一 (seed, key) 永远得到同一结果，保证同一轮内多次构建请求结果一致
        fun rng(key: Any): Random = Random(seed * 31L + key.hashCode())

        val extra = assistantPrompt?.takeIf { lorebook.scanAssistantPrompt && !it.isBlank() }

        // 逐条评估的上下文按条目的 scanDepth 各自裁剪（index 为窗口最后一条消息的下标）
        fun contextFor(entry: PromptInjection.RegexInjection, index: Int): String {
            val depth = entry.scanDepth.coerceAtLeast(1)
            val from = (index + 1 - depth).coerceAtLeast(0)
            val to = (index + 1).coerceAtMost(messages.size)
            return buildString {
                for (i in from until to) {
                    append(messages[i].toText())
                    append('\n')
                }
                if (extra != null) append(extra)
            }
        }

        val lastIndex = messages.size - 1
        val messageCount = messages.size

        // 在指定历史下标处该条目是否命中（用于 sticky/cooldown 重放）
        fun matchedAt(entry: PromptInjection.RegexInjection, index: Int): Boolean =
            index >= 0 && index <= lastIndex && entry.isTriggered(contextFor(entry, index))

        // 时序判定：直接激活（sticky）/ 直接抑制（cooldown）/ 需要走常规匹配。
        // 从历史重放激活序列（activation = 命中且不在冷却期），取「上一条消息之前的最后一次激活」
        // 作为当前消息的 sticky/cooldown 依据，因此连续出现的关键词也能在冷却结束后重新激活。
        fun timedState(entry: PromptInjection.RegexInjection): TimedState {
            if (messageCount < entry.delay) return TimedState.Inactive
            if (entry.sticky <= 0 && entry.cooldown <= 0) return TimedState.Normal

            val lookback = maxOf(entry.sticky, entry.cooldown + 1) + entry.delay + 2
            val from = (messageCount - lookback).coerceAtLeast(0)
            var lastActivation = -1
            for (m in from until messageCount - 1) {                if (m < entry.delay) continue
                // 粘性期内不刷新时长
                if (lastActivation >= 0 && m - lastActivation < entry.sticky) continue
                // 冷却期内不能激活
                if (lastActivation >= 0 && m - lastActivation <= entry.cooldown) continue
                if (matchedAt(entry, m)) lastActivation = m
            }
            if (lastActivation < 0) return TimedState.Normal
            // 当前消息的下标（messageCount 是条数，下标要减一）
            val elapsed = (messageCount - 1) - lastActivation
            if (entry.sticky > 0 && elapsed < entry.sticky) return TimedState.StickyActive
            if (entry.cooldown > 0 && elapsed <= entry.cooldown) return TimedState.Inactive
            return TimedState.Normal
        }

        val active = LinkedHashMap<Uuid, PromptInjection.RegexInjection>()
        val stickyIds = mutableSetOf<Uuid>()
        val scores = HashMap<Uuid, Int>()

        fun tryActivate(entry: PromptInjection.RegexInjection, extraContext: String? = null) {
            if (entry.id in active) return
            when (timedState(entry)) {
                TimedState.Inactive -> return
                TimedState.StickyActive -> {
                    active[entry.id] = entry
                    stickyIds.add(entry.id)
                    return
                }

                TimedState.Normal -> Unit
            }
            val context = contextFor(entry, lastIndex) + (extraContext ?: "")
            if (!entry.isTriggered(context)) return
            active[entry.id] = entry
            scores[entry.id] = entry.matchScore(context)
        }

        // 首轮：delayUntilRecursion 的条目不参与
        entries.filter { it.enabled && !it.delayUntilRecursion }.forEach { tryActivate(it) }

        // 递归：把已激活条目的内容并入匹配文本，继续激活未命中的条目
        if (lorebook.recursiveScanning && lorebook.maxRecursionSteps > 0) {
            var step = 1
            while (step <= lorebook.maxRecursionSteps) {
                if (active.values.any { it.preventRecursion }) break

                val recursiveText = active.values.joinToString("\n") { it.content }
                if (recursiveText.isBlank()) break

                val before = active.size
                entries.forEach { entry ->
                    if (!entry.enabled || entry.excludeRecursion || entry.id in active) return@forEach
                    tryActivate(entry, "\n$recursiveText")
                }
                if (active.size == before) break
                step++
            }
        }

        if (active.isEmpty()) return emptyList()

        // 激活数不足时向后扩大扫描窗口（忽略条目自己的 scanDepth 下限）
        var resolved: List<PromptInjection.RegexInjection> = active.values.toList()
        if (lorebook.minActivations > 0 && resolved.size < lorebook.minActivations) {
            resolved = widenScan(resolved, entries, messages, extra)
        }

        // 分组竞争：同组同时命中只保留一条
        resolved = resolveGroups(resolved, scores, seed)

        // 概率：sticky 激活的条目跳过概率检查
        resolved = resolved.filter { entry ->
            entry.id in stickyIds ||
                !entry.useProbability ||
                entry.probability >= 100 ||
                rng(entry.id).nextInt(100) < entry.probability.coerceIn(0, 100)
        }

        // token 预算：按 priority 从高到低填充，超出部分丢弃
        val budgeted = applyBudget(resolved, lorebook.tokenBudget)

        // 按书籍中的条目顺序稳定排序：同一激活集合永远拼出相同字节，是缓存命中的前提
        val order = entries.withIndex().associate { (index, entry) -> entry.id to index }
        return budgeted.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    private enum class TimedState { Normal, StickyActive, Inactive }

    /** minActivations：把扫描深度放宽到全部消息后重试一次 */
    private fun widenScan(
        current: List<PromptInjection.RegexInjection>,
        entries: List<PromptInjection.RegexInjection>,
        messages: List<UIMessage>,
        extra: String?,
    ): List<PromptInjection.RegexInjection> {
        if (messages.isEmpty()) return current
        val context = buildString {
            messages.forEach {
                append(it.toText())
                append('\n')
            }
            if (extra != null) append(extra)
        }
        val result = current.toMutableList()
        val seen = current.map { it.id }.toMutableSet()
        entries.filter { it.enabled }.forEach { entry ->
            if (entry.id in seen) return@forEach
            if (entry.isTriggered(context)) {
                result.add(entry)
                seen.add(entry.id)
            }
        }
        return result
    }

    /** 同组条目只保留一条：优先评分 → 优先级 → 加权随机（确定性） */
    private fun resolveGroups(
        entries: List<PromptInjection.RegexInjection>,
        scores: Map<Uuid, Int>,
        seed: Long,
    ): List<PromptInjection.RegexInjection> {
        val grouped = entries.filter { it.group.isNotBlank() }.groupBy { it.group }
        if (grouped.isEmpty()) return entries

        val dropped = mutableSetOf<Uuid>()
        grouped.forEach { (groupName, groupEntries) ->
            if (groupEntries.size <= 1) return@forEach
            var candidates = groupEntries
            if (groupEntries.any { it.useGroupScoring }) {
                val best = candidates.maxOf { scores[it.id] ?: 0 }
                candidates = candidates.filter { (scores[it.id] ?: 0) == best }
            }
            val chosen: PromptInjection.RegexInjection = when {
                candidates.any { it.groupOverride } -> candidates.maxByOrNull { it.priority } ?: candidates.first()
                else -> {
                    val total = candidates.sumOf { it.groupWeight.coerceAtLeast(0) }
                    if (total <= 0) {
                        candidates.first()
                    } else {
                        var ticket = Random(seed * 31L + groupName.hashCode()).nextInt(total)
                        candidates.first { entry ->
                            ticket -= entry.groupWeight.coerceAtLeast(0)
                            ticket < 0
                        }
                    }
                }
            }
            candidates.forEach { if (it.id != chosen.id) dropped.add(it.id) }
        }
        return if (dropped.isEmpty()) entries else entries.filter { it.id !in dropped }
    }

    /** token 预算按字符近似：priority 高的优先保留 */
    private fun applyBudget(
        entries: List<PromptInjection.RegexInjection>,
        tokenBudget: Int,
    ): List<PromptInjection.RegexInjection> {
        if (tokenBudget <= 0) return entries
        var remaining = tokenBudget
        val keptIds = mutableSetOf<Uuid>()
        entries.sortedByDescending { it.priority }.forEach { entry ->
            val cost = entry.content.length
            if (cost > remaining) return@forEach
            remaining -= cost
            keptIds.add(entry.id)
        }
        // 保持原有相对顺序，便于稳定拼装
        return entries.filter { it.id in keptIds }
    }
}
