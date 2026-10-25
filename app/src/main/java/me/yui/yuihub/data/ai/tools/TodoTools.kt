package me.yui.yuihub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

const val TODO_TOOL_NAME = "todo_write"

/** 单个待办的状态 */
private val TODO_STATUSES = listOf("pending", "in_progress", "completed")

/**
 * 计划工具（Claude Code / Cursor 风格的 TodoWrite）。
 *
 * 模型把多步任务拆成显式清单并持续更新，UI 用专门的卡片渲染进度，
 * 让长任务的过程可见、可中断；同时引导模型先规划再动手，减少跑偏。
 *
 * 工具本身无副作用（不落库）：最新一次调用的参数就是当前清单，
 * 按调用顺序渲染为「计划 -> 更新 -> 更新」的时间线，与消息历史一起持久化。
 */
fun createTodoTool(): Tool = Tool(
    name = TODO_TOOL_NAME,
    description = """
        Create or update the task checklist for the current work. It replaces any previous list and is
        shown to the user live, so report progress step by step instead of only at the end.

        Rules:
        - Call it BEFORE the first step of a multi-step task (3+ distinct steps). Skip it for simple
          single-step requests.
        - Before starting a step, call it again and mark that step "in_progress". As soon as the step is
          finished, call it again and mark it "completed" — before starting the next step.
        - Never mark several steps completed in one call, and never wait until the whole task is done to
          update: the user watches the list to see which step is running right now.
        - Always pass the FULL list (not just the changed items); previously sent items must be repeated.
        - Exactly one item may be "in_progress" at a time; mark an item "completed" only when it is
          truly done (tests passing, file written, etc.).
        - Keep items short, actionable, and starting with a verb (e.g. "Add DB migration").
        - Do not mention this tool or the plan bookkeeping in your final answer to the user; the UI
          already shows the checklist.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("todos", buildJsonObject {
                    put("type", "array")
                    put("description", "The complete todo list; replaces any previous list")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Short imperative description of the step")
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { TODO_STATUSES.forEach { add(it) } })
                                put("description", "pending | in_progress | completed")
                            })
                        })
                        put("required", buildJsonArray {
                            add("content")
                            add("status")
                        })
                    })
                })
            },
            required = listOf("todos"),
        )
    },
    execute = { args ->
        val todos = args.jsonObject["todos"] as? JsonArray
            ?: error("todos is required and must be an array")
        val normalized = todos.mapIndexed { index, element ->
            val item = element.jsonObject
            val content = item["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (content.isBlank()) error("todos[$index].content must not be blank")
            val status = item["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (status !in TODO_STATUSES) {
                error("todos[$index].status must be one of ${TODO_STATUSES.joinToString()}")
            }
            buildJsonObject {
                put("content", content)
                put("status", status)
            }
        }
        val inProgressCount = normalized.count {
            it["status"]?.jsonPrimitive?.contentOrNull == "in_progress"
        }
        val inProgressItem = normalized.firstOrNull {
            it["status"]?.jsonPrimitive?.contentOrNull == "in_progress"
        }?.get("content")?.jsonPrimitive?.contentOrNull
        val pendingItem = normalized.firstOrNull {
            it["status"]?.jsonPrimitive?.contentOrNull == "pending"
        }?.get("content")?.jsonPrimitive?.contentOrNull
        val payload = buildJsonObject {
            put("todos", JsonArray(normalized))
            // 状态提醒：弱模型常常只建清单、不再回来更新，这里把下一步该做什么直接回给模型
            when {
                inProgressCount > 1 -> put(
                    "warning",
                    "More than one item is in_progress ($inProgressCount). Keep exactly one."
                )

                inProgressCount == 0 && pendingItem != null -> put(
                    "warning",
                    "No item is in_progress. Mark the step you are about to start as in_progress."
                )
            }
            put(
                "next",
                when {
                    inProgressItem != null ->
                        "Working on \"$inProgressItem\". Call todo_write again the moment it is done: mark it completed and the next step in_progress."

                    pendingItem != null ->
                        "Call todo_write again before starting \"$pendingItem\": mark it in_progress."

                    else -> "All steps are completed."
                }
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
