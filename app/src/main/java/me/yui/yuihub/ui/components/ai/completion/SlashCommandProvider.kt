package me.yui.yuihub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange

/**
 * 输入栏斜杠命令补全：输入以 "/" 开头且 "/" 后无空白时，在输入框上方列出命令列表。
 * 点击列表项填入命令文本（如 "/压缩"）。
 */
class SlashCommandProvider(
    private val commands: List<Command>,
) : ChatCompletionProvider {
    override val id: String = "slash_commands"

    data class Command(
        val trigger: String,
        val description: String,
    )

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection) return null
        // 只处理完整光标处仍是命令前缀的情况：文本以 "/" 开头且其到光标之间没有空白
        val prefix = context.text.substring(0, context.cursor.coerceIn(0, context.text.length))
        if (!prefix.startsWith("/")) return null
        val token = prefix.removePrefix("/")
        if (token.any { it.isWhitespace() }) return null
        // 已经敲成完整命令（后面接空格或换行）后不再弹列表
        if (context.text.length > context.cursor) {
            val next = context.text[context.cursor]
            if (next.isWhitespace()) return null
        }

        val items = commands
            .filter { command ->
                val name = command.trigger.removePrefix("/")
                // 空输入列出全部；部分输入按前缀过滤；已完整输入命令本身时收起列表
                token.isBlank() || (name.startsWith(token) && name != token)
            }
            .map { command ->
                ChatCompletionItem(
                    label = command.trigger,
                    insertText = command.trigger,
                    detail = command.description,
                )
            }
        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = TextRange(0, context.cursor),
            items = items,
        )
    }
}
