package me.yui.yuihub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区路径安全规则单测（这些规则直接决定文件/shell 工具的越权面）：
 * - /upload 只读保护：write_file / edit_file 必须拒绝，防止误覆盖用户上传的原始文件
 * - 可写安全区判定：/workspace、/tmp、/skills 免强制审批，其余路径需要审批
 */
class WorkspaceToolsPathRulesTest {

    @Test
    fun `upload path is read only`() {
        assertTrue("/upload".isInReadOnlyRoot())
        assertTrue("/upload/file.txt".isInReadOnlyRoot())
        assertTrue("/upload/nested/deep.pdf".isInReadOnlyRoot())
        // 尾斜杠与大小写不变式
        assertTrue("/upload/".isInReadOnlyRoot())
    }

    @Test
    fun `paths outside upload are writable`() {
        assertFalse("/workspace/a.txt".isInReadOnlyRoot())
        assertFalse("/tmp/x".isInReadOnlyRoot())
        // 不得把前缀相同的兄弟目录误判为只读
        assertFalse("/uploads/a.txt".isInReadOnlyRoot())
        assertFalse("/upload-backup/a.txt".isInReadOnlyRoot())
    }

    @Test
    fun `reject read only path throws`() {
        val e = assertThrows(IllegalStateException::class.java) { rejectReadOnlyPath("/upload/original.pdf") }
        assertTrue(e.message.orEmpty().startsWith("READ_ONLY"))
    }

    @Test
    fun `reject read only path passes through writable paths`() {
        rejectReadOnlyPath("/workspace/copy.pdf")
        rejectReadOnlyPath("/tmp/scratch.txt")
    }

    @Test
    fun `writable roots are free of approval`() {
        assertFalse("/workspace/a.txt".isOutsideWritableRoots())
        assertFalse("/tmp/a.txt".isOutsideWritableRoots())
        assertFalse("/skills/my-skill/SKILL.md".isOutsideWritableRoots())
    }

    @Test
    fun `outside paths require approval`() {
        assertTrue("/upload/a.txt".isOutsideWritableRoots())
        assertTrue("/etc/passwd".isOutsideWritableRoots())
        assertTrue("/workspace-other/a.txt".isOutsideWritableRoots())
    }
}
