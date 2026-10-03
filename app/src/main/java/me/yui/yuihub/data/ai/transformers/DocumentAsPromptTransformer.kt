package me.yui.yuihub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File
import java.util.Locale

/**
 * 上传文件注入策略（封面先行 + 按需拆解）：
 *
 * 附件文件本体存放在 filesDir/upload，经 proot 挂载到 workspace 的 /upload
 * （工具层只读保护，shell 可绕过，见 RepositoryModule / WorkspaceBindMount 注释）。
 * - 小文本文件（≤[INLINE_MAX_BYTES]）：全文内联，一次读完最高效；
 * - 其它文件（大文件/二进制）：只注入「封面」——元信息 + 结构摘要 + 读取指引，
 *   由 AI 用 workspace 工具（read_file / shell head/grep）按需分步拆解，
 *   避免整份内容撑爆请求体积（"请求体积过大"错误的根源）。
 *
 * 封面必须给 AI 足够的线索判断文件里有什么、该用什么工具读哪一段。
 */
object DocumentAsPromptTransformer : InputMessageTransformer {
    // 解析缓存：同一文件（路径+mtime+size 不变）在进程内只解析一次
    // （每个 step 都会重建请求，历史文档不应重复解析）；文件被修改后 key 变化自动失效。
    private const val CACHE_MAX_ENTRIES = 4
    private const val CACHE_MAX_CHARS = 800_000
    private val parseCache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean =
            size > CACHE_MAX_ENTRIES
    }

    // 封面里给预览片段的长度上限
    private const val COVER_PREVIEW_CHARS = 600

    // 全文内联阈值：以内的小文本直接注入全文（约 24KB，~6K tokens）
    private const val INLINE_MAX_BYTES = 24L * 1024

    // 超过该大小的大文本不做全文提取（解析出来也内联不下），封面直接给读取指引
    private const val PARSE_MAX_BYTES = 8L * 1024 * 1024

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val prompt = buildDocumentPrompt(document)
                                if (prompt != null) {
                                    add(0, UIMessagePart.Text(prompt))
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * 生成单个附件的注入文本：小文本全文，其它封面。
     * 返回 null 表示文件缺失（无可注入内容）。
     * internal 供 JVM 单测直接验证封面逻辑（TransformerContext 携带 Android 依赖无法在 JVM 构造）。
     */
    internal fun buildDocumentPrompt(document: UIMessagePart.Document): String? {
        val file = resolveLocalFile(document.url)
            ?: return errorCover(document, "invalid file uri")
        if (!file.exists() || !file.isFile) {
            return errorCover(document, "file not found")
        }
        val uploadPath = resolveWorkspacePath(document) ?: "/upload/${file.name}"
        val sizeBytes = file.length()
        val isTextLike = isTextLikeFile(document, file)

        // 小文本：全文内联（沿用解析缓存）
        if (isTextLike && sizeBytes <= INLINE_MAX_BYTES) {
            val content = readDocumentContent(document)
            return buildCover(document, file, uploadPath, inlineBody = content)
        }

        // 结构化文档（pdf/docx/pptx/epub）且不太大：提取文本，全文放得下就内联，否则给节选
        val structured = extractStructuredText(document, file)
        if (structured != null) {
            return if (structured.length <= COVER_PREVIEW_CHARS * 4) {
                buildCover(document, file, uploadPath, inlineBody = structured)
            } else {
                val preview = structured.take(COVER_PREVIEW_CHARS)
                buildCover(
                    document, file, uploadPath,
                    summary = "Extracted text is ${structured.length} chars; beginning preview below.",
                    preview = preview,
                    hint = "This is only the beginning. Read the rest step by step " +
                        "with workspace_read_file (text output) or workspace_shell (grep/head/tail on the extracted copy) " +
                        "instead of requesting the whole file at once.",
                )
            }
        }

        // 超过内联阈值的普通文本文件：封面 = 行数/预览 + 分步读取指引
        if (isTextLike) {
            val preview = runCatching {
                file.useLines { lines ->
                    lines.take(20).joinToString("\n").take(COVER_PREVIEW_CHARS)
                }
            }.getOrDefault("")
            val lineCount = runCatching { file.readLines().size }.getOrDefault(-1)
            return buildCover(
                document, file, uploadPath,
                summary = buildString {
                    append("Plain-text file too large to inline")
                    if (lineCount >= 0) append(", $lineCount lines")
                    append(".")
                },
                preview = preview,
                hint = "This is only the beginning. Read the rest step by step with workspace_read_file " +
                    "or workspace_shell (sed -n '10,50p', grep -n, head/tail) — fetch only the sections you need.",
            )
        }

        // 二进制/超大文件：纯封面 + 拆解指引
        return buildCover(
            document, file, uploadPath,
            summary = binarySummary(document, file),
            hint = "Do NOT try to read this file whole. Inspect it step by step with workspace_shell " +
                "(file/ls -la/head/strings/unzip -l/xxd | head) or copy it into /workspace and unpack there. " +
                "Read only the parts you need for the current question.",
        )
    }

    private fun buildCover(
        document: UIMessagePart.Document,
        file: File,
        uploadPath: String,
        summary: String? = null,
        preview: String? = null,
        inlineBody: String? = null,
        hint: String? = null,
    ): String = buildString {
        appendLine("<UploadFile name=\"${document.fileName}\" path=\"$uploadPath\" size=\"${formatSize(file.length())}\" mime=\"${document.mime}\">")
        if (inlineBody != null) {
            appendLine("```")
            appendLine(inlineBody)
            appendLine("```")
        } else {
            appendLine("<cover>")
            summary?.let { appendLine(it) }
            preview?.let {
                appendLine("--- preview ---")
                appendLine("```")
                appendLine(it)
                appendLine("```")
            }
            hint?.let { appendLine(it) }
            appendLine("</cover>")
        }
        append("</UploadFile>")
    }

    private fun errorCover(document: UIMessagePart.Document, reason: String): String =
        "<UploadFile name=\"${document.fileName}\">[ERROR, $reason]</UploadFile>"

    // ---- 分类与摘要 ----

    private val TEXT_MIME_PREFIXES = listOf("text/", "application/json", "application/xml", "application/javascript", "application/x-yaml", "application/toml")

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "log", "html", "htm", "css", "js", "ts", "jsx", "tsx", "kt", "kts", "java", "py", "rb", "go",
        "rs", "c", "h", "cpp", "hpp", "cs", "swift", "sh", "bash", "sql", "gradle", "properties",
        "php", "lua", "pl", "r", "dart", "vue", "svelte", "proto", "graphql", "gql", "env", "diff", "patch",
    )

    private fun isTextLikeFile(document: UIMessagePart.Document, file: File): Boolean {
        if (TEXT_MIME_PREFIXES.any { document.mime.startsWith(it) }) return true
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in TEXT_EXTENSIONS
    }

    /**
     * 结构化文档提取文本。返回 null 表示非结构化类型（走二进制封面）。
     * 超过 PARSE_MAX_BYTES 的结构化文档不提取（提取结果也远超内联预算）。
     */
    private fun extractStructuredText(document: UIMessagePart.Document, file: File): String? {
        if (file.length() > PARSE_MAX_BYTES) return null
        val isStructured = when (document.mime) {
            "application/pdf" -> true
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> true
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> true
            "application/epub+zip" -> true
            else -> false
        }
        if (!isStructured) return null
        val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        synchronized(parseCache) { parseCache[cacheKey] }?.let { return it }
        val content = runCatching {
            when (document.mime) {
                "application/pdf" -> PdfParser.parserPdf(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PptxParser.parse(file)
                "application/epub+zip" -> EpubParser.parse(file)
                else -> return null
            }
        }.getOrElse {
            return errorCover(document, "failed to parse structured document")
        }
        if (content.length <= CACHE_MAX_CHARS) {
            synchronized(parseCache) { parseCache[cacheKey] = content }
        }
        return content
    }

    /** 二进制文件的封面摘要：魔数 + 常见容器识别 */
    private fun binarySummary(document: UIMessagePart.Document, file: File): String {
        val magic = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(8)
                val read = input.read(head)
                if (read <= 0) "" else head.joinToString(" ") { "%02x".format(it) }
            }
        }.getOrDefault("unreadable")
        val kind = detectBinaryKind(document, file)
        return "Binary or oversized file (${formatSize(file.length())}, magic: $magic). $kind"
    }

    private fun detectBinaryKind(document: UIMessagePart.Document, file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return when {
            document.mime == "application/zip" || ext in setOf("zip", "apk", "jar", "ipa", "docx", "pptx", "xlsx") ->
                "Likely a ZIP container — inspect with `unzip -l` to list entries before extracting."
            ext in setOf("tar", "gz", "tgz", "bz2", "xz", "zst") ->
                "Likely a tar/compressed archive — inspect with `tar -tf` before extracting."
            ext in setOf("mp4", "mkv", "mov", "avi", "webm") -> "Video file — cannot be viewed as text."
            ext in setOf("mp3", "wav", "flac", "ogg", "m4a") -> "Audio file — cannot be viewed as text."
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> "Image file — read with workspace_read_file (image) if visual analysis is needed."
            else -> "Unknown binary format — identify with `file` command if needed."
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1fMB".format(Locale.ROOT, bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.1fKB".format(Locale.ROOT, bytes / 1024.0)
        else -> "${bytes}B"
    }


    /**
     * 解析附件 url 为宿主文件。androidx 的 toUri()/toFile() 依赖 android.net.Uri（JVM 单测未 mock），
     * 这里优先走纯 java.io 路径（file:// 前缀或纯路径），失败再回落 androidx 解析（content:// 等）。
     */
    private fun resolveLocalFile(url: String): File? {
        if (url.startsWith("file://")) {
            val path = java.net.URI(url).path
            if (path != null) return File(path)
        }
        if (url.startsWith("/")) return File(url)
        return runCatching { url.toUri().toFile() }.getOrNull()
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = resolveLocalFile(document.url) ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    // 全文内联路径复用统一的解析入口（含缓存），保证小文本与历史行为一致
    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = resolveLocalFile(document.url)
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        synchronized(parseCache) { parseCache[cacheKey] }?.let { return it }
        val content = runCatching {
            when (document.mime) {
                "application/pdf" -> PdfParser.parserPdf(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PptxParser.parse(file)
                "application/epub+zip" -> EpubParser.parse(file)
                else -> file.readText()
            }
        }.getOrElse {
            return "[ERROR, failed to read file: ${document.fileName}]"
        }
        if (content.length <= CACHE_MAX_CHARS) {
            synchronized(parseCache) { parseCache[cacheKey] = content }
        }
        return content
    }
}
