package me.yui.yuihub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.yui.yuihub.data.ai.tools.local.LocalToolOption
import me.yui.yuihub.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    // 上下文消息条数上限, 超出后阶梯式截断; 0 表示不限制
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val enableWebSearch: Boolean = false, // 网络搜索开关(每个助手独立)
    val workspaceId: Uuid? = null,
    val background: String? = null, // 聊天页背景图地址(本地文件 URI 或网络 URL), 为 null 时无背景
    val backgroundOpacity: Float = 1.0f, // 背景图不透明度(0~1)
    val useGradientBackground: Boolean = false, // 开启后聊天页使用动态渐变背景
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val timeReminderIntervalMinutes: Int = 60,          // 时间提醒间隔（分钟，至少 1 分钟）
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowConversationPromptInjection: Boolean = false, // 允许对话单独绑定提示词注入
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val importance: Float = 0.5f,
    val category: String = MemoryCategory.OTHER,
)

/**
 * 记忆类别：固定六类，存储英文常量，界面按语言映射显示。
 */
object MemoryCategory {
    const val PROFILE = "profile"
    const val PREFERENCE = "preference"
    const val CODING = "coding"
    const val ROLEPLAY = "roleplay"
    const val DAILY = "daily"
    const val TEMPORARY = "temporary"
    const val OTHER = "other"

    val ALL = listOf(PROFILE, PREFERENCE, CODING, ROLEPLAY, DAILY, TEMPORARY, OTHER)

    fun normalize(category: String?): String {
        val value = category?.trim()?.lowercase().orEmpty()
        return if (value in ALL) value else OTHER
    }
}

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
)

// 流式输出时每个chunk都会调用replaceRegexes，正则必须缓存编译结果，
// 否则长回复期间会重复编译上万次；编译失败也缓存，避免反复构造异常
private val regexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

private fun compileRegexCached(pattern: String): Regex? {
    regexCache.getIfPresent(pattern)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern) }.onFailure { it.printStackTrace() }
    regexCache.put(pattern, result)
    return result.getOrNull()
}

// 世界书关键词匹配正则会因时序重放/递归多轮/逐条目评估反复编译，同样需要缓存
private val keywordRegexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

internal fun cachedKeywordRegex(pattern: String): Regex? {
    keywordRegexCache.getIfPresent(pattern)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern) }
    keywordRegexCache.put(pattern, result)
    return result.getOrNull()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            val compiled = compileRegexCached(regex.findRegex) ?: return@fold acc
            try {
                acc.replace(
                    regex = compiled,
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                // 替换字符串可能引用不存在的分组，失败时返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）
}

/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    /**
     * 正则注入 - 基于内容匹配触发（世界书条目）
     *
     * 字段对齐 SillyTavern World Info / Character Card V2-V3 的主流语义（除自有 priority 排序）。
     * 全部新增字段都有默认值，旧数据可直接反序列化。
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),  // 主键（触发关键词）
        val useRegex: Boolean = false,             // 键是否按正则解释
        val caseSensitive: Boolean = false,        // 大小写敏感
        val scanDepth: Int = 4,                    // 扫描最近N条消息
        val constantActive: Boolean = false,       // 常驻激活（无需匹配）
        // 次级键 + 选择性逻辑
        val secondaryKeywords: List<String> = emptyList(),
        val selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY,
        // 匹配细节
        val matchWholeWords: Boolean = true,       // 全词匹配（中文建议关闭）
        // 概率触发
        val useProbability: Boolean = true,
        val probability: Int = 100,                // 0-100，命中后仍有概率不插入
        // 递归
        val excludeRecursion: Boolean = false,     // 不被其他条目激活
        val preventRecursion: Boolean = false,     // 激活后不再触发其他条目
        val delayUntilRecursion: Boolean = false,  // 仅在递归阶段生效
        // 分组（同组同时命中只取一条）
        val group: String = "",
        val groupWeight: Int = 100,
        val groupOverride: Boolean = false,        // 改为确定性地取 priority 最高者
        val useGroupScoring: Boolean = false,      // 按命中键数先筛高分
        // 时序效果（单位：消息条数，0 表示无效）
        val sticky: Int = 0,                       // 激活后持续 N 条消息（期间忽略概率）
        val cooldown: Int = 0,                     // 激活后 N 条消息内不再激活
        val delay: Int = 0,                        // 消息数达到 N 之前不能激活
    ) : PromptInjection()
}

/**
 * 次级键的选择性逻辑，对齐 SillyTavern / CCv2 的 selectiveLogic。
 */
@Serializable
enum class SelectiveLogic {
    @SerialName("and_any")
    AND_ANY,   // 至少命中一个次级键

    @SerialName("and_all")
    AND_ALL,   // 全部次级键命中

    @SerialName("not_any")
    NOT_ANY,   // 一个次级键也不得命中

    @SerialName("not_all")
    NOT_ALL,   // 不允许全部次级键命中
}

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
    // 递归扫描：条目内容中出现的其他条目关键词可继续激活
    val recursiveScanning: Boolean = false,
    val maxRecursionSteps: Int = 3,
    // token 预算（按字符数近似，0 表示不限制）
    val tokenBudget: Int = 0,
    // 激活数量不足时向后扩大扫描窗口（忽略 scanDepth 下限）
    val minActivations: Int = 0,
    val maxScanDepth: Int = 0,      // minActivations 回溯的上限，0 表示不额外限制
    // 把助手系统提示词也纳入匹配文本（对齐 ST 的 additional matching sources）
    val scanAssistantPrompt: Boolean = false,
)

/**
 * 检查 RegexInjection 是否被触发
 *
 * @param context 要扫描的上下文文本
 * @return 是否触发
 */
fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false

    if (!primaryMatched(context)) return false
    return secondaryMatched(context)
}

/** 主键是否命中（任一主键命中即可） */
internal fun PromptInjection.RegexInjection.primaryMatched(context: String): Boolean =
    keywords.any { matchesKeyword(context, it) }

/**
 * 次级键选择性逻辑。次级键为空时视为通过。
 */
internal fun PromptInjection.RegexInjection.secondaryMatched(context: String): Boolean {
    if (secondaryKeywords.isEmpty()) return true
    val hits = secondaryKeywords.count { matchesKeyword(context, it) }
    return when (selectiveLogic) {
        SelectiveLogic.AND_ANY -> hits >= 1
        SelectiveLogic.AND_ALL -> hits == secondaryKeywords.size
        SelectiveLogic.NOT_ANY -> hits == 0
        SelectiveLogic.NOT_ALL -> hits < secondaryKeywords.size
    }
}

/** 命中键数（主键命中记 1 分，次级键按命中数累加），用于分组评分 */
internal fun PromptInjection.RegexInjection.matchScore(context: String): Int {
    var score = 0
    if (keywords.any { matchesKeyword(context, it) }) score += 1
    score += secondaryKeywords.count { matchesKeyword(context, it) }
    return score
}

/**
 * 单个键的匹配。全词匹配只对 ASCII 词性键生效（\b 对中日韩无意义，强行加会永不命中）。
 */
internal fun PromptInjection.RegexInjection.matchesKeyword(context: String, keyword: String): Boolean {
    if (keyword.isEmpty()) return false
    if (useRegex) {
        // 大小写不敏感统一用内联标志进 pattern，这样缓存 key 唯一
        val flags = if (caseSensitive) "" else "(?i)"
        return cachedKeywordRegex(flags + keyword)?.containsMatchIn(context) ?: false
    }
    if (matchWholeWords && keyword.isAsciiWord()) {
        // 手动处理边界：\b 在中文/标点相邻时行为不稳定，这里用"非字母数字下划线"判定
        val flags = if (caseSensitive) "" else "(?i)"
        val pattern = "$flags(?<![A-Za-z0-9_])${Regex.escape(keyword)}(?![A-Za-z0-9_])"
        return cachedKeywordRegex(pattern)?.containsMatchIn(context) ?: false
    }
    return context.contains(keyword, ignoreCase = !caseSensitive)
}

/** 键是否为纯 ASCII 词（可以安全做全词边界匹配） */
internal fun String.isAsciiWord(): Boolean =
    isNotBlank() && all { it.code < 128 && (it.isLetterOrDigit() || it == '_' || it == '-') }

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    return messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
}
