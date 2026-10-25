package me.yui.yuihub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolSupportTest {

    @Test
    fun `physical lines handle CRLF and trailing LF`() {
        assertEquals(listOf("a", "b"), splitPhysicalLines("a\r\nb\n"))
        assertEquals(listOf("a", ""), splitPhysicalLines("a\n\n"))
        assertEquals(emptyList<String>(), splitPhysicalLines(""))
        assertEquals(listOf(""), splitPhysicalLines("\n"))
    }

    @Test
    fun `numbered lines keep physical line numbers`() {
        val lines = listOf("alpha", "beta", "gamma")
        assertEquals("2|beta\n3|gamma", renderNumberedLines(lines, 1, 3))
        assertEquals("", renderNumberedLines(lines, 3, 3))
    }

    @Test
    fun `sha256 hex is stable`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abc".toByteArray().sha256Hex(),
        )
    }

    @Test
    fun `read cache invalidates only the changed path`() {
        val cache = WorkspaceReadCache()
        val a = readCacheKey("/workspace/a.kt", 1, 20, "rev-a")
        val b = readCacheKey("/workspace/b.kt", 1, 20, "rev-b")
        cache.record(a)
        cache.record(b)
        assertTrue(cache.isUnchanged(a))
        cache.invalidate("/workspace/a.kt")
        assertFalse(cache.isUnchanged(a))
        assertTrue(cache.isUnchanged(b))
        cache.clearAll()
        assertFalse(cache.isUnchanged(b))
    }

    @Test
    fun `activation is isolated and rejects unknown names`() {
        val tool = Tool(
            name = "workspace_glob",
            description = "test",
            execute = { listOf(UIMessagePart.Text("ok")) },
        )
        val activation = WorkspaceToolActivation(mapOf("workspace_glob" to { tool }))
        assertEquals(emptyList<String>(), activation.activeTools().map { it.name })
        assertEquals(listOf("workspace_glob"), activation.activate(listOf("workspace_glob")))
        assertEquals(listOf("workspace_glob"), activation.activeTools().map { it.name })
        assertEquals(emptyList<String>(), activation.activate(listOf("workspace_glob")))
        assertTrue(runCatching { activation.activate(listOf("missing")) }.isFailure)
    }
}
