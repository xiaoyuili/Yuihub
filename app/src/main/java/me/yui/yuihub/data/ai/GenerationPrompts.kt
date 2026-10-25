package me.yui.yuihub.data.ai

// Agent 工具循环的输出风格约束：只约束工具轮与最终回答的节奏，不重复具体工具说明。
internal val AGENT_TOOL_STYLE_PROMPT = """# Tool-use style
- Use tools only when they materially improve accuracy or the task cannot be answered without them.
- Keep user-facing prose minimal during tool use; put reasoning in the thinking channel. One short status before a long run is enough.
- Run independent calls in parallel; do not repeat an identical call.
- Keep any task checklist live: exactly one step in_progress at a time, mark a step completed the moment it is done,
  then start the next. Never save all the status updates for the end of the task.
- Stop when done. The final answer is complete and direct, with no step narration.
""".trimIndent()
