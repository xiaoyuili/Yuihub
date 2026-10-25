package me.yui.yuihub.ui.pages.setting

import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页搜索排序与命中规则：
 * 标题 > 关键词 > 分组 > 描述；多关键词是 AND；同分保持索引顺序。
 */
class SettingsSearchTest {

    private fun item(
        title: String,
        group: String,
        order: Int,
        description: String? = null,
        keywords: List<String> = emptyList(),
    ) = SettingsSearchItem(
        title = title,
        description = description,
        keywords = keywords,
        group = group,
        order = order,
        payload = title,
    )

    private val items = listOf(
        item("外观", "通用设置", 0, keywords = listOf("主题", "输入栏", "模糊")),
        item("默认模型", "AI 配置", 1, keywords = listOf("自动压缩", "视觉模型")),
        item("供应商", "AI 配置", 2, keywords = listOf("API Key", "Base URL")),
        item("提示词", "AI 配置", 3, keywords = listOf("系统提示", "记忆提取")),
        item("工作区", "能力", 4, keywords = listOf("workspace", "挂载")),
    )

    @Test
    fun `empty query returns nothing`() {
        assertTrue(searchSettings(items, "   ").isEmpty())
    }

    @Test
    fun `title match ranks first`() {
        val hits = searchSettings(items, "模型")
        assertEquals("默认模型", hits.first().item.title)
        assertTrue(hits.first().score > 0)
    }

    @Test
    fun `keyword match reports the keyword`() {
        val hits = searchSettings(items, "模糊")
        assertEquals(1, hits.size)
        assertEquals("外观", hits.single().item.title)
        assertEquals("模糊", hits.single().keyword)
    }

    @Test
    fun `multiple tokens are all required`() {
        // 两个词都命中才返回
        assertEquals(
            listOf("供应商"),
            searchSettings(items, "供应 API").map { it.item.title },
        )
        assertTrue(searchSettings(items, "供应商 不存在的词").isEmpty())
    }

    @Test
    fun `group name is searchable`() {
        val titles = searchSettings(items, "AI 配置").map { it.item.title }
        assertTrue("默认模型" in titles && "供应商" in titles && "提示词" in titles)
        assertTrue("工作区" !in titles)
    }

    @Test
    fun `title hit outranks keyword hit`() {
        val target = listOf(
            item("主题", "通用设置", 0),                    // 标题直接命中
            item("外观", "通用设置", 1, keywords = listOf("主题")), // 关键词命中
        )
        assertEquals(listOf("主题", "外观"), searchSettings(target, "主题").map { it.item.title })
    }

    @Test
    fun `matching is case insensitive`() {
        val titles = searchSettings(items, "api key").map { it.item.title }
        assertEquals(listOf("供应商"), titles)
    }

    @Test
    fun `highlight marks all occurrences and merges overlaps`() {
        val annotated = highlightSettingsText(
            text = "供应商供应商",
            tokens = listOf("供应", "供应商"),
            style = SpanStyle(),
        )
        assertEquals("供应商供应商", annotated.text)
        val spans = annotated.spanStyles
        assertEquals(1, spans.size)
        assertEquals(0, spans.single().start)
        assertEquals(6, spans.single().end)
    }

    @Test
    fun `highlight without match has no spans`() {
        val annotated = highlightSettingsText("供应商", listOf("模型"), SpanStyle())
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun `chat background is reachable by plain keyword`() {
        val catalog = listOf(
            item(
                title = "聊天背景",
                group = "设置 › 外观 › 消息界面",
                order = 0,
                keywords = listOf("背景", "背景图", "壁纸"),
            ),
        )
        val hits = searchSettings(catalog, "背景")
        assertEquals(listOf("聊天背景"), hits.map { it.item.title })
        // 标题里就有“背景”，直接命中标题，不需要回退到关键词提示
        assertEquals(null, hits.single().keyword)
    }

    @Test
    fun `catalog item is reachable by its page path`() {
        val catalog = listOf(
            item("聊天背景", "设置 › 外观 › 消息界面", 0, keywords = listOf("背景")),
            item("输入栏背景效果", "设置 › 外观 › 交互", 1, keywords = listOf("模糊")),
        )
        assertEquals(
            listOf("聊天背景"),
            searchSettings(catalog, "消息界面").map { it.item.title },
        )
        assertEquals(
            listOf("输入栏背景效果"),
            searchSettings(catalog, "交互").map { it.item.title },
        )
    }
}
