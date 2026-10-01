package me.yui.yuihub.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogMatcherTest {

    // ---------- 归一化 ----------

    @Test
    fun `normalize lowercases and unifies separators`() {
        assertEquals("claude-opus-4-5", ModelCatalogMatcher.normalize("Claude_Opus_4.5"))
        assertEquals("claude-opus-4-5", ModelCatalogMatcher.normalize("Claude-Opus-4.5"))
    }

    @Test
    fun `normalize strips provider prefix`() {
        assertEquals("claude-haiku-4-5", ModelCatalogMatcher.normalize("anthropic/claude-haiku-4-5"))
        assertEquals("kimi-k2", ModelCatalogMatcher.normalize("moonshotai/kimi-k2"))
        assertEquals("kimi-k2", ModelCatalogMatcher.normalize("openrouter:moonshotai/kimi-k2"))
        assertEquals("laguna-s-2-1", ModelCatalogMatcher.normalize("poolside/laguna-s-2.1"))
    }

    @Test
    fun `normalize strips colon suffix`() {
        assertEquals("laguna-s-2-1", ModelCatalogMatcher.normalize("poolside/laguna-s-2.1:free"))
        // 冒号在路径段前（hf: 前缀）时不能把整段吞掉
        assertEquals("kimi-k3", ModelCatalogMatcher.normalize("hf:moonshotai/Kimi-K3"))
    }

    @Test
    fun `normalize handles empty input`() {
        assertEquals("", ModelCatalogMatcher.normalize(""))
        assertEquals("", ModelCatalogMatcher.normalize("  "))
        assertEquals("", ModelCatalogMatcher.normalize("///"))
    }

    // ---------- 打分 ----------

    @Test
    fun `exact key scores highest`() {
        assertEquals(1000, ModelCatalogMatcher.score("gpt-4o", "gpt-4o"))
    }

    @Test
    fun `dated eight digit variant matches undated key`() {
        assertEquals(850, ModelCatalogMatcher.score("claude-haiku-4-5-20251001", "claude-haiku-4-5"))
        assertEquals(850, ModelCatalogMatcher.score("claude-haiku-4-5", "claude-haiku-4-5-20251001"))
    }

    @Test
    fun `dashed date suffix matches via prefix boundary`() {
        // -YYYY-MM-DD 形式不属于纯八位日期，走前缀边界（700）
        assertEquals(700, ModelCatalogMatcher.score("gpt-4o-2024-11-20", "gpt-4o"))
        assertEquals(700, ModelCatalogMatcher.score("gpt-4o", "gpt-4o-2024-11-20"))
    }

    @Test
    fun `hyphen boundary prefix matches but not partial segment`() {
        assertEquals(700, ModelCatalogMatcher.score("claude-haiku-4-5", "claude-haiku-4-5-turbo"))
        // 不是连字符边界：claude-haiku-4 不应匹配 claude-haiku-45
        assertNull(ModelCatalogMatcher.score("claude-haiku-4", "claude-haiku-45"))
    }

    @Test
    fun `vendor prefixed query matches via token suffix`() {
        // meta-llama-3-8b-instruct 的 token 尾部是 llama-3-8b-instruct
        assertEquals(400, ModelCatalogMatcher.score("meta-llama-3-8b-instruct", "llama-3-8b-instruct"))
    }

    @Test
    fun `subsequence requires enough tokens and small gap`() {
        // gemini-2-0-flash-001 vs gemini-flash-001：跳过 "2 0" 两个 token（间隔 <= 4）
        assertEquals(250, ModelCatalogMatcher.score("gemini-2-0-flash-001", "gemini-flash-001"))
        // 完全无关的 ID 不应匹配
        assertNull(ModelCatalogMatcher.score("totally-custom-model-xyz", "claude-haiku-4-5"))
    }

    @Test
    fun `unrelated model does not match`() {
        assertNull(ModelCatalogMatcher.score("my-finetune-v7", "gpt-4o"))
        assertNull(ModelCatalogMatcher.score("my-finetune-v7", "claude-haiku-4-5"))
    }

    // ---------- 解析与查询（真实数据 fixture） ----------

    private val json = Json { ignoreUnknownKeys = true }

    private val catalog: Map<String, List<ModelCatalogEntry>> by lazy {
        // 原始 models.dev 格式（provider -> models -> rawId）的子集，
        // 走完整的 parse -> resolve 链路而非直接注入解析后的结构
        val body = checkNotNull(javaClass.classLoader?.getResourceAsStream("model_catalog_raw_fixture.json")) {
            "fixture not found"
        }.use { it.readBytes().decodeToString() }
        ModelCatalogResolver.parse(json, body)
    }

    @Test
    fun `fixture parses into normalized groups`() {
        assertTrue(catalog.containsKey("claude-haiku-4-5"))
        assertTrue(catalog.containsKey("gemini-2-5-flash"))
        assertTrue(catalog.containsKey("kimi-k2"))
        assertEquals(18, catalog.size)
    }

    @Test
    fun `resolves claude with context vision tool and reasoning`() {
        val info = checkNotNull(
            ModelCatalogResolver.resolve(catalog, "claude-haiku-4-5", "Anthropic", "https://api.anthropic.com/v1")
        )
        assertEquals(200_000, info.contextLength)
        assertTrue(info.inputHasImage)
        assertFalse(info.outputHasImage)
        assertTrue(info.toolCall)
        assertTrue(info.reasoning)
    }

    @Test
    fun `resolves without provider hint using first party preference`() {
        val info = checkNotNull(ModelCatalogResolver.resolve(catalog, "Claude-Opus-4.5", null, null))
        assertEquals(200_000, info.contextLength)
        assertTrue(info.inputHasImage)
    }

    @Test
    fun `dated and prefixed ids resolve to same model`() {
        val dated = checkNotNull(ModelCatalogResolver.resolve(catalog, "claude-haiku-4-5-20251001", null, null))
        val prefixed = checkNotNull(ModelCatalogResolver.resolve(catalog, "anthropic/claude-haiku-4-5", null, null))
        assertEquals(200_000, dated.contextLength)
        assertEquals(200_000, prefixed.contextLength)
    }

    @Test
    fun `resolves gpt-4o with openai and azure hints`() {
        val openai = checkNotNull(ModelCatalogResolver.resolve(catalog, "gpt-4o", "OpenAI", "https://api.openai.com/v1"))
        val azure = checkNotNull(ModelCatalogResolver.resolve(catalog, "gpt-4o", "Azure", "https://x.openai.azure.com"))
        assertEquals(128_000, openai.contextLength)
        assertEquals(128_000, azure.contextLength)
        assertTrue(openai.inputHasImage)
        assertTrue(openai.toolCall)
        assertFalse(openai.reasoning)
    }

    @Test
    fun `resolves o3-mini with majority capacity`() {
        val info = checkNotNull(ModelCatalogResolver.resolve(catalog, "o3-mini", null, null))
        assertEquals(200_000, info.contextLength)
        assertTrue(info.toolCall)
        assertTrue(info.reasoning)
        assertFalse(info.inputHasImage)
    }

    @Test
    fun `resolves gemini 2_5 flash from dotted input`() {
        val info = checkNotNull(
            ModelCatalogResolver.resolve(catalog, "gemini-2.5-flash", "Google", "https://generativelanguage.googleapis.com/v1beta")
        )
        assertEquals(1_048_576, info.contextLength)
        assertTrue(info.inputHasImage)
        assertTrue(info.toolCall)
    }

    @Test
    fun `resolves image model with image output`() {
        val info = checkNotNull(
            ModelCatalogResolver.resolve(catalog, "gemini-2.5-flash-image", "Google", "https://generativelanguage.googleapis.com/v1beta")
        )
        assertTrue(info.outputHasImage)
        assertTrue(info.inputHasImage)
    }

    @Test
    fun `resolves deepseek chat`() {
        val info = checkNotNull(
            ModelCatalogResolver.resolve(catalog, "deepseek-chat", "DeepSeek", "https://api.deepseek.com/v1")
        )
        assertEquals(128_000, info.contextLength)
        assertFalse(info.inputHasImage)
        assertTrue(info.toolCall)
        assertFalse(info.reasoning)
    }

    @Test
    fun `resolves kimi k2 via vendor prefix inside data`() {
        val info = checkNotNull(ModelCatalogResolver.resolve(catalog, "kimi-k2", "Moonshot", "https://api.moonshot.cn/v1"))
        assertEquals(131_072, info.contextLength)
    }

    @Test
    fun `resolves qwen max`() {
        val info = checkNotNull(
            ModelCatalogResolver.resolve(catalog, "qwen-max", "Alibaba", "https://dashscope.aliyuncs.com/api/v1")
        )
        assertEquals(32_768, info.contextLength)
    }

    @Test
    fun `unknown model returns null`() {
        assertNull(ModelCatalogResolver.resolve(catalog, "totally-custom-model-xyz", null, null))
        assertNull(ModelCatalogResolver.resolve(catalog, "my-finetune-v7", "My Relay", "https://relay.example.com/v1"))
    }

    @Test
    fun `blank model id returns null`() {
        assertNull(ModelCatalogResolver.resolve(catalog, "   ", null, null))
    }

    // ---------- 供应商提示提取 ----------

    @Test
    fun `provider hints from base url and name`() {
        assertEquals(
            listOf("deepseek"),
            ModelCatalogResolver.providerHints("DeepSeek", "https://api.deepseek.com/v1")
        )
        assertEquals(
            listOf("google"),
            ModelCatalogResolver.providerHints("Google", "https://generativelanguage.googleapis.com/v1beta")
        )
        assertTrue(ModelCatalogResolver.providerHints("SiliconFlow", "https://api.siliconflow.cn/v1").contains("siliconflow"))
    }

    @Test
    fun `provider hints skip stop words and short labels`() {
        // api / com / cn 是保留词，只剩 example
        assertEquals(listOf("example"), ModelCatalogResolver.providerHints(null, "https://api.example.com.cn/v1"))
        assertEquals(emptyList<String>(), ModelCatalogResolver.providerHints("API", "http://ai.io/v1"))
    }

    // ---------- 仲裁 ----------

    @Test
    fun `hint exact provider wins context vote`() {
        val group = listOf(
            ModelCatalogEntry(provider = "302ai", context = 100_000, inputHasImage = true),
            ModelCatalogEntry(provider = "anthropic", context = 200_000, inputHasImage = true),
        )
        val info = ModelCatalogResolver.arbitrate(group, listOf("anthropic"))
        assertEquals(200_000, info.contextLength)
    }

    @Test
    fun `context tie picks larger value`() {
        val group = listOf(
            ModelCatalogEntry(provider = "a", context = 100_000),
            ModelCatalogEntry(provider = "b", context = 128_000),
        )
        assertEquals(128_000, ModelCatalogResolver.arbitrate(group, emptyList()).contextLength)
    }

    @Test
    fun `capability flag needs half the weight`() {
        val group = listOf(
            ModelCatalogEntry(provider = "a", inputHasImage = true),
            ModelCatalogEntry(provider = "b", inputHasImage = true),
            ModelCatalogEntry(provider = "c", inputHasImage = false),
        )
        assertTrue(ModelCatalogResolver.arbitrate(group, emptyList()).inputHasImage)
    }
}
