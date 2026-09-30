package me.yui.yuihub.data.ai.transformers

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 封面先行注入策略回归（直接调 internal buildDocumentPrompt；
 * transform 需要的 TransformerContext 携带 Android 依赖，JVM 侧无法构造）：
 * - 小文本全文内联（≤24KB）
 * - 大文本只注入封面（元信息+预览+拆解指引），请求体积可控
 * - 二进制文件给魔数+容器识别+工具指引
 */
class DocumentAsPromptTransformerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun doc(file: java.io.File, mime: String) = UIMessagePart.Document(
        url = "file://" + file.absolutePath,
        fileName = file.name,
        mime = mime,
    )

    private fun promptOf(file: java.io.File, mime: String): String =
        DocumentAsPromptTransformer.buildDocumentPrompt(doc(file, mime))!!

    @Test
    fun `small text file is inlined in full`() {
        val upload = tmp.newFolder("upload")
        val file = upload.resolve("small.txt").apply { writeText("hello world\n".repeat(10)) }
        val injected = promptOf(file, "text/plain")
        assertTrue("全文应被内联", "hello world" in injected)
        assertFalse("小文件不需要拆解指引", "step by step" in injected)
        assertTrue("应带 /upload 路径", "path=\"/upload/small.txt\"" in injected)
    }

    @Test
    fun `large text file becomes a cover with preview and guidance`() {
        val upload = tmp.newFolder("upload")
        val big = upload.resolve("big.txt").apply {
            writeText((1..30_000).joinToString("\n") { "line $it some content to pad the file size" })
        }
        assertTrue("测试前置：文件须超过内联阈值", big.length() > 24 * 1024)
        val injected = promptOf(big, "text/plain")
        assertTrue("封面应含大小信息", "size=" in injected)
        assertTrue("封面应有预览", "preview" in injected)
        assertTrue("应有拆解指引", "step by step" in injected)
        assertTrue(
            "注入体积应远小于原文（防请求体积过大的关键）",
            injected.length < big.length().toInt() / 4,
        )
        assertFalse("不得内联全文", "line 29999" in injected)
    }

    @Test
    fun `binary file gets magic and container hints`() {
        val upload = tmp.newFolder("upload")
        val zip = upload.resolve("bundle.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64))
        }
        val injected = promptOf(zip, "application/octet-stream")
        assertTrue("应有魔数", "magic: 50 4b 03 04" in injected)
        assertTrue("APK 应识别为 ZIP 容器", "ZIP container" in injected)
        assertTrue("应给 unzip 指引", "unzip -l" in injected)
        assertTrue("应给分步指引", "step by step" in injected)
        assertFalse("二进制不得内联内容", "<cover>" !in injected)
    }

    @Test
    fun `missing file degrades to error cover instead of crashing`() {
        val missing = tmp.root.resolve("upload/ghost.txt")
        val injected = promptOf(missing, "text/plain")
        assertTrue("[ERROR" in injected)
    }
}
