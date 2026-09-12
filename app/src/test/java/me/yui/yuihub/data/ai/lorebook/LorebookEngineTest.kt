package me.yui.yuihub.data.ai.lorebook

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.model.Lorebook
import me.yui.yuihub.data.model.PromptInjection
import me.yui.yuihub.data.model.SelectiveLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookEngineTest {

    private fun msg(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun entry(
        name: String = "e",
        keywords: List<String> = emptyList(),
        content: String = "content",
        scanDepth: Int = 10,
        constantActive: Boolean = false,
        secondaryKeywords: List<String> = emptyList(),
        selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY,
        matchWholeWords: Boolean = true,
        useProbability: Boolean = true,
        probability: Int = 100,
        excludeRecursion: Boolean = false,
        preventRecursion: Boolean = false,
        delayUntilRecursion: Boolean = false,
        group: String = "",
        groupWeight: Int = 100,
        groupOverride: Boolean = false,
        useGroupScoring: Boolean = false,
        sticky: Int = 0,
        cooldown: Int = 0,
        delay: Int = 0,
    ) = PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = name,
        keywords = keywords,
        content = content,
        scanDepth = scanDepth,
        constantActive = constantActive,
        secondaryKeywords = secondaryKeywords,
        selectiveLogic = selectiveLogic,
        matchWholeWords = matchWholeWords,
        useProbability = useProbability,
        probability = probability,
        excludeRecursion = excludeRecursion,
        preventRecursion = preventRecursion,
        delayUntilRecursion = delayUntilRecursion,
        group = group,
        groupWeight = groupWeight,
        groupOverride = groupOverride,
        useGroupScoring = useGroupScoring,
        sticky = sticky,
        cooldown = cooldown,
        delay = delay,
    )

    private fun book(
        vararg entries: PromptInjection.RegexInjection,
        recursiveScanning: Boolean = false,
        tokenBudget: Int = 0,
        minActivations: Int = 0,
        scanAssistantPrompt: Boolean = false,
    ) = Lorebook(
        name = "book",
        entries = entries.toList(),
        recursiveScanning = recursiveScanning,
        tokenBudget = tokenBudget,
        minActivations = minActivations,
        scanAssistantPrompt = scanAssistantPrompt,
    )

    private fun namesOf(result: List<PromptInjection.RegexInjection>) = result.map { it.name }.toSet()

    // ===== 触发匹配 =====

    @Test
    fun `primary key match activates entry`() {
        val e = entry(name = "a", keywords = listOf("dragon"))
        val result = LorebookEngine.resolveEntries(book(e), listOf(msg("there is a dragon")))
        assertEquals(setOf("a"), namesOf(result))
    }

    @Test
    fun `constant active entry needs no keywords`() {
        val e = entry(name = "a", constantActive = true)
        val result = LorebookEngine.resolveEntries(book(e), listOf(msg("nothing")))
        assertEquals(setOf("a"), namesOf(result))
    }

    @Test
    fun `whole word match ignores substrings`() {
        val e = entry(name = "a", keywords = listOf("cat"))
        val result = LorebookEngine.resolveEntries(book(e), listOf(msg("category theory")))
        assertTrue(result.isEmpty())

        val hit = LorebookEngine.resolveEntries(book(e), listOf(msg("a cat sleeps")))
        assertEquals(setOf("a"), namesOf(hit))
    }

    @Test
    fun `whole word match does not break CJK keywords`() {
        val e = entry(name = "a", keywords = listOf("龙"))
        val result = LorebookEngine.resolveEntries(book(e), listOf(msg("这里有一条龙在飞")))
        assertEquals(setOf("a"), namesOf(result))
    }

    // ===== 次级键与选择性逻辑 =====

    @Test
    fun `and_any needs at least one secondary key`() {
        val e = entry(name = "a", keywords = listOf("dragon"), secondaryKeywords = listOf("fire", "ice"))
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon with ice"))).isNotEmpty())
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon alone"))).isEmpty())
    }

    @Test
    fun `and_all needs every secondary key`() {
        val e = entry(
            name = "a",
            keywords = listOf("dragon"),
            secondaryKeywords = listOf("fire", "ice"),
            selectiveLogic = SelectiveLogic.AND_ALL,
        )
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon with fire and ice"))).isNotEmpty())
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon with fire"))).isEmpty())
    }

    @Test
    fun `not_any rejects when any secondary key matches`() {
        val e = entry(
            name = "a",
            keywords = listOf("dragon"),
            secondaryKeywords = listOf("fire"),
            selectiveLogic = SelectiveLogic.NOT_ANY,
        )
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon in the cave"))).isNotEmpty())
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon with fire"))).isEmpty())
    }

    @Test
    fun `not_all rejects only when every secondary key matches`() {
        val e = entry(
            name = "a",
            keywords = listOf("dragon"),
            secondaryKeywords = listOf("fire", "ice"),
            selectiveLogic = SelectiveLogic.NOT_ALL,
        )
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon with fire"))).isNotEmpty())
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon with fire and ice"))).isEmpty())
    }

    // ===== 时序效果 =====

    @Test
    fun `delay blocks activation until enough messages`() {
        val e = entry(name = "a", keywords = listOf("dragon"), delay = 3, scanDepth = 10)
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon"), msg("x"))).isEmpty())
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon"), msg("x"), msg("y"))).isNotEmpty())
    }

    @Test
    fun `sticky keeps entry active after the keyword leaves the scan window`() {
        // scanDepth=1：当前窗口只看最后一条消息，普通匹配已看不到 dragon
        val messages = listOf(msg("dragon"), msg("hello"), msg("world"))
        val sticky = entry(name = "a", keywords = listOf("dragon"), scanDepth = 1, sticky = 3)
        assertEquals(setOf("a"), namesOf(LorebookEngine.resolveEntries(book(sticky), messages)))

        // sticky 窗口不够长时自然失效
        val shortSticky = entry(name = "a", keywords = listOf("dragon"), scanDepth = 1, sticky = 2)
        assertTrue(LorebookEngine.resolveEntries(book(shortSticky), messages).isEmpty())
    }

    @Test
    fun `cooldown suppresses entry that triggered recently`() {
        val messages = listOf(msg("dragon"), msg("x"), msg("y"))
        val e = entry(name = "a", keywords = listOf("dragon"), scanDepth = 1, cooldown = 3)
        assertTrue(LorebookEngine.resolveEntries(book(e), messages).isEmpty())

        // 冷却期外可以再次触发
        val outside = listOf(msg("dragon"), msg("x"), msg("y"), msg("z"))
        assertTrue(LorebookEngine.resolveEntries(book(e), outside).isEmpty())
    }

    // ===== 递归 =====

    @Test
    fun `recursion activates entry whose keyword appears in another entry content`() {
        val a = entry(name = "a", keywords = listOf("apple"), content = "mentions banana")
        val b = entry(name = "b", keywords = listOf("banana"), content = "b content")
        val result = LorebookEngine.resolveEntries(
            book(a, b, recursiveScanning = true),
            listOf(msg("apple")),
        )
        assertEquals(setOf("a", "b"), namesOf(result))
    }

    @Test
    fun `recursion disabled leaves second entry inactive`() {
        val a = entry(name = "a", keywords = listOf("apple"), content = "mentions banana")
        val b = entry(name = "b", keywords = listOf("banana"))
        val result = LorebookEngine.resolveEntries(book(a, b), listOf(msg("apple")))
        assertEquals(setOf("a"), namesOf(result))
    }

    @Test
    fun `excludeRecursion prevents entry from being activated recursively`() {
        val a = entry(name = "a", keywords = listOf("apple"), content = "mentions banana")
        val b = entry(name = "b", keywords = listOf("banana"), excludeRecursion = true)
        val result = LorebookEngine.resolveEntries(
            book(a, b, recursiveScanning = true),
            listOf(msg("apple")),
        )
        assertEquals(setOf("a"), namesOf(result))
    }

    @Test
    fun `preventRecursion stops further recursion`() {
        val a = entry(name = "a", keywords = listOf("apple"), content = "mentions banana", preventRecursion = true)
        val b = entry(name = "b", keywords = listOf("banana"))
        val result = LorebookEngine.resolveEntries(
            book(a, b, recursiveScanning = true),
            listOf(msg("apple")),
        )
        assertEquals(setOf("a"), namesOf(result))
    }

    @Test
    fun `delayUntilRecursion entry only activates in recursion phase`() {
        val lonely = entry(name = "b", keywords = listOf("banana"), delayUntilRecursion = true)
        assertTrue(LorebookEngine.resolveEntries(book(lonely), listOf(msg("banana"))).isEmpty())

        val a = entry(name = "a", keywords = listOf("apple"), content = "mentions banana")
        val result = LorebookEngine.resolveEntries(
            book(a, lonely, recursiveScanning = true),
            listOf(msg("apple")),
        )
        assertEquals(setOf("a", "b"), namesOf(result))
    }

    // ===== 分组竞争 =====

    @Test
    fun `group with prioritize inclusion picks highest priority`() {
        val low = entry(name = "low", keywords = listOf("dragon"), group = "g", groupOverride = true)
            .let { it.copy(priority = 1) }
        val high = entry(name = "high", keywords = listOf("dragon"), group = "g", groupOverride = true)
            .let { it.copy(priority = 9) }
        val result = LorebookEngine.resolveEntries(book(low, high), listOf(msg("dragon")))
        assertEquals(setOf("high"), namesOf(result))
    }

    @Test
    fun `group keeps only one entry`() {
        val a = entry(name = "a", keywords = listOf("dragon"), group = "g", groupWeight = 100)
        val b = entry(name = "b", keywords = listOf("dragon"), group = "g", groupWeight = 100)
        val result = LorebookEngine.resolveEntries(
            book(a, b),
            listOf(msg("dragon")),
            seed = 42L,
        )
        assertEquals(1, result.size)
    }

    // ===== 缓存稳定性 =====

    @Test
    fun `same seed yields identical result across repeated builds`() {
        // 同一轮对话内会构建多次请求（每个工具 step 一次），结果必须逐字一致，否则前缀缓存失效
        val e = entry(name = "a", keywords = listOf("dragon"), probability = 50)
        val b = book(e)
        val messages = listOf(msg("dragon"))
        val first = LorebookEngine.resolveEntries(b, messages, seed = 7L)
        val second = LorebookEngine.resolveEntries(b, messages, seed = 7L)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `resolved order follows book entry order`() {
        val first = entry(name = "first", keywords = listOf("dragon"))
        val second = entry(name = "second", keywords = listOf("dragon"))
        // 反序传入（模拟激活顺序与书内顺序不一致）
        val result = LorebookEngine.resolveEntries(book(first, second), listOf(msg("dragon")))
        assertEquals(listOf("first", "second"), result.map { it.name })
    }

    @Test
    fun `probability is stable per seed and can differ across seeds`() {
        val e = entry(name = "a", keywords = listOf("dragon"), probability = 50)
        val b = book(e)
        val messages = listOf(msg("dragon"))
        // 扫一批种子，确认不是恒定值（确实有随机性）也不是每调用都变（确定性）
        val results = (0L until 40L).map {
            LorebookEngine.resolveEntries(b, messages, seed = it).isNotEmpty()
        }
        assertTrue(results.any { it })
        assertTrue(results.any { !it })
    }

    // ===== 概率与预算 =====

    @Test
    fun `zero probability prevents insertion`() {
        val e = entry(name = "a", keywords = listOf("dragon"), probability = 0)
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon"))).isEmpty())

        val disabled = entry(name = "a", keywords = listOf("dragon"), useProbability = false, probability = 0)
        assertEquals(setOf("a"), namesOf(LorebookEngine.resolveEntries(book(disabled), listOf(msg("dragon")))))
    }

    @Test
    fun `token budget keeps highest priority entries`() {
        val big = entry(name = "big", keywords = listOf("dragon"), content = "x".repeat(100))
            .let { it.copy(priority = 1) }
        val small = entry(name = "small", keywords = listOf("dragon"), content = "x".repeat(10))
            .let { it.copy(priority = 9) }
        val result = LorebookEngine.resolveEntries(
            book(big, small, tokenBudget = 20),
            listOf(msg("dragon")),
        )
        assertEquals(setOf("small"), namesOf(result))
    }

    // ===== 额外匹配源 =====

    @Test
    fun `scanAssistantPrompt matches keywords in the assistant prompt`() {
        val e = entry(name = "a", keywords = listOf("wizard"))
        val b = book(e, scanAssistantPrompt = true)
        assertTrue(LorebookEngine.resolveEntries(b, listOf(msg("hello"))).isEmpty())
        assertEquals(
            setOf("a"),
            namesOf(LorebookEngine.resolveEntries(b, listOf(msg("hello")), assistantPrompt = "You are a wizard.")),
        )
    }

    @Test
    fun `disabled entry never activates`() {
        val e = entry(name = "a", keywords = listOf("dragon"), constantActive = true).copy(enabled = false)
        assertTrue(LorebookEngine.resolveEntries(book(e), listOf(msg("dragon"))).isEmpty())
    }

    @Test
    fun `assistant prompt is ignored when book option is off`() {
        val e = entry(name = "a", keywords = listOf("wizard"))
        assertTrue(
            LorebookEngine.resolveEntries(book(e), listOf(msg("hello")), assistantPrompt = "wizard").isEmpty()
        )
    }

    @Test
    fun `recursion stops when no new entry activates`() {
        val a = entry(name = "a", keywords = listOf("apple"), content = "no other keyword")
        val b = entry(name = "b", keywords = listOf("banana"))
        val result = LorebookEngine.resolveEntries(book(a, b, recursiveScanning = true), listOf(msg("apple")))
        assertEquals(setOf("a"), namesOf(result))
        assertFalse(result.isEmpty())
    }
}
