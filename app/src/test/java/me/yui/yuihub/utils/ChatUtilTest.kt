package me.yui.yuihub.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件类型白名单已放开为全类型接受（agent 客户端，AI 经工具链处理任意文件）。
 * 函数保留签名以兼容调用点；回归断言防止未来误恢复拒绝行为。
 */
class ChatUtilTest {
    @Test
    fun `allows previously whitelisted types`() {
        assertTrue(isAllowedFileType("camera-config.agc", "application/octet-stream"))
        assertTrue(isAllowedFileType("notes.txt", "text/plain"))
        assertTrue(isAllowedFileType("doc.pdf", "application/pdf"))
    }

    @Test
    fun `accepts unknown and binary types`() {
        assertTrue(isAllowedFileType("archive.unknown", "application/octet-stream"))
        assertTrue(isAllowedFileType("data.bin", "application/x-binary"))
        assertTrue(isAllowedFileType("noext", ""))
    }
}
