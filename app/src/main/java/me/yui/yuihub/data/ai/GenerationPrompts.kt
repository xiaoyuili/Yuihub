package me.yui.yuihub.data.ai

// Agent 工具循环的输出风格约束：只在最终回答轮写面向用户的文本，
// 工具轮不碎碎念（对齐 harness/Claude Code 的 思考→工具→…→最终输出 结构）
internal val AGENT_TOOL_STYLE_PROMPT = """
# Tool-use style
- While you still need tools, emit tool calls back-to-back: no user-facing prose between calls; keep deliberation in the thinking channel.
- Never narrate steps ("Now I will check...", "Let me look...") in turns that call tools.
- Write the complete user-facing answer only in the FINAL turn, once all tool work is done. A one-line status before a long tool run is the only exception.
""".trimIndent()
