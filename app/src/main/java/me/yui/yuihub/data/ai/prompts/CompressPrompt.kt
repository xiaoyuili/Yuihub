package me.yui.yuihub.data.ai.prompts

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

// 检查点前导语：告知续接模型把摘要当既有背景、不复述不承认（对齐 deepseek-harness compaction-basic）
internal const val COMPACTION_CHECKPOINT_PREAMBLE =
    "This is an automatically generated checkpoint for an earlier conversation span. " +
        "Treat it as established background, do not restate it, and continue from the following messages " +
        "without acknowledging this checkpoint."

internal const val COMPACTION_SUMMARY_OPEN = "<compressed-summary>"
internal const val COMPACTION_SUMMARY_CLOSE = "</compressed-summary>"

// 检查点完整文本 = 前导语 + <compressed-summary> 正文；请求期构造，不落库为消息
fun buildCompactionCheckpointText(
    summaryContent: String,
    preamble: String = COMPACTION_CHECKPOINT_PREAMBLE,
): String = buildString {
    appendLine(preamble)
    appendLine()
    append(COMPACTION_SUMMARY_OPEN)
    appendLine()
    append(summaryContent)
    appendLine()
    append(COMPACTION_SUMMARY_CLOSE)
}

// 是否为自动压缩产生的检查点消息（UI 据此渲染为压缩流程行而非用户气泡）
fun UIMessage.isCompactionCheckpoint(): Boolean {
    if (role != MessageRole.USER || parts.size != 1) return false
    val part = parts[0] as? UIMessagePart.Text ?: return false
    // 前导语可编辑，用固定结构标签识别检查点
    return part.text.contains(COMPACTION_SUMMARY_OPEN) && part.text.contains(COMPACTION_SUMMARY_CLOSE)
}

// 从检查点文本中抽取摘要正文（<compressed-summary> 块内内容）
fun compactionCheckpointBody(text: String): String {
    val start = text.indexOf(COMPACTION_SUMMARY_OPEN)
    val end = text.indexOf(COMPACTION_SUMMARY_CLOSE)
    return if (start >= 0 && end > start) {
        text.substring(start + COMPACTION_SUMMARY_OPEN.length, end).trim()
    } else {
        text
    }
}

internal val DEFAULT_SUMMARY_MERGE_PROMPT = """Consolidate the checkpoint summaries below into one self-contained checkpoint.

Preserve the original goal, user constraints, decisions with rationale, exact paths/commands/errors/identifiers/numbers, unfinished work, and the next step. Drop duplicates and stale facts. Do not invent.

Output only Markdown. Target approximately {target_tokens} tokens.

<summaries>
{summaries}
</summaries>"""

internal val DEFAULT_COMPRESS_PROMPT = """You are the context-compaction engine. Summarize <conversation> for another model that must continue the task without the original messages.

Write a compact Markdown checkpoint with these sections, in order. Use terse bullets; write "(none)" for an empty section.

# Goal
- Original and current user intent, constraints, acceptance criteria.

# State
- What is done, what is in progress, what failed.

# Decisions
- Choices and rationale; user corrections, quoted exactly when wording matters.

# Artifacts
- Exact file paths, commands, error strings, identifiers, numbers, signatures, and only the code snippets needed to continue.

# Open
- Pending work, blockers, and the single next action.

Rules:
- Keep only information the next model needs; drop logs, duplicate reads, and dead ends unless they contain a lesson.
- For tool output, keep the conclusion plus any saved path under `/tool_outputs`; do not paste bulk logs.
- Do not invent facts. Mark uncertainty explicitly.
- Do not mention this request or that compaction happened.
- Do not call tools.
- Write in {locale}; target approximately {target_tokens} tokens.

{additional_context}

<conversation>
{content}
</conversation>"""
