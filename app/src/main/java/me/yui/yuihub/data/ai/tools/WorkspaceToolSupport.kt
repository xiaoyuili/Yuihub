package me.yui.yuihub.data.ai.tools

import kotlinx.coroutines.sync.Mutex
import me.yui.yuihub.data.repository.WorkspaceRepository
import java.security.MessageDigest

internal const val WORKSPACE_READ_CACHE_MAX_CHARS = 24_000

internal data class WorkspaceTextSnapshot(
    val path: String,
    val text: String,
    val revision: String,
    val lines: List<String>,
    val sizeBytes: Long,
)

/**
 * 读取 Rootfs 文本并一次性算好行与 revision。
 * revision 基于原始 UTF-8 字节，不受行范围影响。
 */
internal suspend fun WorkspaceRepository.readTextSnapshot(
    workspaceId: String,
    path: String,
): WorkspaceTextSnapshot {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()
    val text = bytes.toString(Charsets.UTF_8)
    require(!text.contains('\uFFFD')) {
        "BINARY_CONTENT: $path is not valid UTF-8 text (lossy decode would corrupt it). " +
            "Use workspace_shell (od/hexdump/base64) for binary files."
    }
    return WorkspaceTextSnapshot(
        path = path,
        text = text,
        revision = bytes.sha256Hex(),
        lines = splitPhysicalLines(text),
        sizeBytes = bytes.size.toLong(),
    )
}

internal suspend fun WorkspaceRepository.currentRevisionOrNull(
    workspaceId: String,
    path: String,
): String? {
    if (rootfsFileMtime(workspaceId, path) == null) return null
    return readRootfsBuffer(workspaceId, path).toByteArray().sha256Hex()
}

internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

/** 物理 LF 行；CRLF 只算一个换行，末尾 LF 不产生额外空行。 */
internal fun splitPhysicalLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = ArrayList<String>()
    var start = 0
    var index = 0
    while (index < text.length) {
        if (text[index] == '\n') {
            var end = index
            if (end > start && text[end - 1] == '\r') end--
            lines += text.substring(start, end)
            start = index + 1
        }
        index++
    }
    if (start < text.length) {
        var end = text.length
        if (end > start && text[end - 1] == '\r') end--
        lines += text.substring(start, end)
    }
    return lines
}

internal fun renderNumberedLines(
    lines: List<String>,
    startIndex: Int,
    endExclusive: Int,
): String = buildString {
    for (index in startIndex until endExclusive) {
        if (isNotEmpty()) append('\n')
        append(index + 1)
        append('|')
        append(lines[index])
    }
}

/**
 * 同一次生成内的重复读取去重。只记录较小的结果，避免一次被全局预算截断的读取
 * 变成“未变化”后模型实际上从未看到完整正文。
 */
internal class WorkspaceReadCache {
    private val keys = HashSet<String>()
    private val pathLocks = HashMap<String, Mutex>()

    @Synchronized
    fun mutex(path: String): Mutex = pathLocks.getOrPut(path) { Mutex() }

    @Synchronized
    fun isUnchanged(key: String): Boolean = key in keys

    @Synchronized
    fun record(key: String) {
        keys += key
    }

    @Synchronized
    fun invalidate(path: String) {
        val prefix = "$path\u0000"
        keys.removeAll { it.startsWith(prefix) }
    }

    @Synchronized
    fun clearAll() {
        keys.clear()
    }
}

internal fun readCacheKey(
    path: String,
    offset: Int,
    limit: Int,
    revision: String,
): String = "$path\u0000$offset\u0000$limit\u0000$revision"
