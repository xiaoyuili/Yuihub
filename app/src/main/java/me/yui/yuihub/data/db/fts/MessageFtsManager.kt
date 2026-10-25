package me.yui.yuihub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.ai.prompts.isMemorySnapshot
import me.yui.yuihub.data.db.AppDatabase
import me.yui.yuihub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

/**
 * 全文搜索：simple 扩展负责英文/数字的分词与 simple_query，
 * 中文由查询侧拆成单字并用 NEAR 限定相邻，不再依赖 jieba 词典。
 */
class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                // 记忆快照是模型侧上下文，不进全文搜索
                if (message.isMemorySnapshot()) return@forEach
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: String? = null,
        conversationId: String? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val matchQuery = buildFtsMatchQuery(keyword, ::simpleQuery) ?: return@withContext emptyList()
        val results = mutableListOf<MessageSearchResult>()
        val filters = mutableListOf<String>()
        val args = mutableListOf(matchQuery)
        if (!assistantId.isNullOrBlank()) {
            filters += "AND conversation_id IN (SELECT id FROM ConversationEntity WHERE assistant_id = ?)"
            args += assistantId
        }
        if (!conversationId.isNullOrBlank()) {
            filters += "AND conversation_id = ?"
            args += conversationId
        }
        val conversationFilter = filters.joinToString("\n")
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH ?
            $conversationFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            args.toTypedArray()
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }

    // simple_query 支持拼音、首字母和前缀匹配
    private fun simpleQuery(text: String): String =
        db.query("SELECT simple_query(?)", arrayOf(text)).use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }
}

private val QUERY_RUN = Regex("[A-Za-z0-9]+|[^\\sA-Za-z0-9]+")

// 中文各字之间最多允许夹几个额外的字（如标点、“的”），再大容易把散落的字也算命中
private const val CJK_NEAR_SLACK = 2

/**
 * 把搜索词拼成 FTS5 查询，各段 AND 连接：
 * 英文/数字交给 [asciiQuery]（simple_query）；中文等连续片段拆成单字用 NEAR 限定相邻。
 */
internal fun buildFtsMatchQuery(keyword: String, asciiQuery: (String) -> String): String? {
    val terms = QUERY_RUN.findAll(keyword).mapNotNull { match ->
        val text = match.value
        val term = if (text.first().isAsciiLetterOrDigit()) asciiQuery(text) else nearQuery(text)
        term.takeIf { it.isNotBlank() }
    }.toList()
    return terms.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
}

private fun nearQuery(text: String): String {
    val chars = text.codePoints().toArray().map {
        "\"${String(Character.toChars(it)).replace("\"", "\"\"")}\""
    }
    if (chars.size == 1) return chars.single()
    return "NEAR(${chars.joinToString(" ")}, ${chars.size - 2 + CJK_NEAR_SLACK})"
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun UIMessage.extractFtsText(): String =
    parts.flatMap { part ->
        when (part) {
            is UIMessagePart.Text -> listOf(part.text)
            is UIMessagePart.Tool -> part.output
                .filterIsInstance<UIMessagePart.Text>()
                .map { it.text }
            else -> emptyList()
        }
    }.joinToString("\n").take(10_000)
