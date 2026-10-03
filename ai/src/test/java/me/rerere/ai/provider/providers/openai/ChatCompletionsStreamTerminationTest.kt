package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流结束语义单测：显式结束（[DONE]）与裸 EOF（中转/网络提前关流）必须可区分。
 *
 * 这是「静默截断」可观测性的基础——只有区分开，上层才能决定是否自动续写、
 * 以及是否给用户「本次响应可能不完整」的提示。
 */
class ChatCompletionsStreamTerminationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun deltaEvent(content: String): SseEvent = SseEvent(
        id = null,
        event = null,
        data = json.encodeToString(
            buildJsonObject {
                put("id", "chatcmpl-1")
                put("model", "test-model")
                put("choices", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject { put("content", content) })
                    })
                })
            }
        ),
    )

    @Test
    fun `explicit DONE yields complete finish`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(deltaEvent("hello"))
        val result = decoder.accept(SseEvent(data = "[DONE]"))

        assertTrue("显式结束应标记 completed", result.completed)
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertFalse("收到 [DONE] 时不得标记 incomplete", finish.incomplete)
        assertTrue("explicitEnd 置位", decoder.explicitEnd)
        assertTrue("已结束后 onClosed 应为幂等空结果", decoder.onClosed().isEmpty())
    }

    @Test
    fun `bare EOF yields incomplete finish`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(deltaEvent("hello wor"))

        // 未收到 [DONE] 就关闭连接：模拟中转提前断流
        val chunks = decoder.onClosed()
        val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertTrue("裸 EOF 必须标记 incomplete", finish.incomplete)
        assertFalse("未收到显式终止，explicitEnd 应为 false", decoder.explicitEnd)
        assertTrue("收尾后 onClosed 幂等", decoder.onClosed().isEmpty())
    }

    @Test
    fun `provider finish_reason length stays incomplete false`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = json.encodeToString(
            buildJsonObject {
                put("id", "chatcmpl-2")
                put("choices", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject { put("content", "partial") })
                        put("finish_reason", "length")
                    })
                })
            }
        )))
        val finish = decoder.accept(SseEvent(data = "[DONE]")).chunks
            .filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("length", finish.finishReason)
        assertFalse("供应商给出的 length 是显式结束，不是裸 EOF", finish.incomplete)
    }
}
