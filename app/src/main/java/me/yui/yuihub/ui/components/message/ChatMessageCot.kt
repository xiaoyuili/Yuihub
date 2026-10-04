package me.yui.yuihub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.utils.JsonInstant

internal const val CHART_DISPLAY_TOOL_NAME = "chart_display"

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep

    /**
     * 工具调用之间的过程解说文本（“现在我来…”“让我看看…”）。
     *
     * 归一进思考链作为可折叠行，而不是渲染成永远可见的正文气泡：
     * 否则会在聊天页堆成一串“碎碎念”，还会把思考链切成多个小块导致折叠失效。
     */
    data class NarrationStep(
        val text: UIMessagePart.Text,
        val index: Int,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock

    /** 成功执行的 chart_display 工具调用, 在正文中以图表卡片展示 */
    data class ChartBlock(val tool: UIMessagePart.Tool, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning、客户端 Tool 和 ServerTool 会被分组到一个 ThinkingBlock 中
 * 成功执行的 chart_display 原地替换为 ChartBlock (会切断所在的 ThinkingBlock);
 * 生成中或失败的调用仍作为普通 ToolStep 展示
 *
 * 计划（todo_write）不单独立块：它作为普通工具步骤留在思考链里（可折叠），
 * 完整清单由输入栏上方的 [PlanBar] 常驻展示，避免同一条计划在正文与细条上重复出现。
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    // 最后一次工具调用之后出现的文本才是面向用户的最终回答；
    // 位于工具调用之前的文本是过程解说，归入思考链一起折叠
    val lastToolIndex = indexOfLast { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                if (part.isSuccessfulChartDisplay()) {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.ChartBlock(part, index))
                } else {
                    currentThinkingSteps.add(ThinkingStep.ToolStep(part))
                }
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            is UIMessagePart.Text -> {
                // 部分模型 (如 Qwen) 会在工具/思考之间输出空白文本，把它当作分隔符
                // 会导致一条思考链被拆成多个块（折叠头消失、块间出现间距），
                // 且空白文本本身不渲染任何内容，这里直接跳过不参与分组。
                if (part.text.isBlank()) return@fastForEachIndexed
                if (index < lastToolIndex) {
                    // 工具活动之前的文本 = 过程解说，并入思考链折叠
                    currentThinkingSteps.add(ThinkingStep.NarrationStep(part, index))
                } else {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.ContentBlock(part, index))
                }
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

private fun UIMessagePart.Tool.isSuccessfulChartDisplay(): Boolean {
    if (toolName != CHART_DISPLAY_TOOL_NAME || !isExecuted) return false
    val outputText = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val result = runCatching { JsonInstant.parseToJsonElement(outputText) }.getOrNull() as? JsonObject
    return (result?.get("success") as? JsonPrimitive)?.booleanOrNull == true
}
