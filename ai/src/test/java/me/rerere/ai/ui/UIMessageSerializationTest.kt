package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessageSerializationTest {

    // isSynthetic 会随消息持久化（记忆快照等场景依赖它跨请求周期保持），
    // 序列化必须无损往返；旧断言「不序列化」与设计相悖
    @Test
    fun `synthetic marker survives serialization roundtrip`() {
        val message = UIMessage.user("internal").copy(isSynthetic = true)

        val encoded = Json.encodeToString(message)
        val decoded = Json.decodeFromString<UIMessage>(encoded)

        assertTrue(message.isSynthetic)
        assertTrue(encoded.contains("isSynthetic"))
        assertTrue(decoded.isSynthetic)
    }

    @Test
    fun `default synthetic marker is false after roundtrip`() {
        val message = UIMessage.user("hello")

        val decoded = Json.decodeFromString<UIMessage>(Json.encodeToString(message))

        assertEquals(false, decoded.isSynthetic)
    }
}
