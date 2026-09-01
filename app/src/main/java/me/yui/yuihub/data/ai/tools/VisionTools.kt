package me.yui.yuihub.data.ai.tools

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.data.files.FilesManager
import me.yui.yuihub.data.repository.WorkspaceRepository
import me.yui.yuihub.service.backgroundTextGenerationParams
import me.yui.yuihub.utils.JsonInstantPretty
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "VisionTools"
private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

const val VISION_TOOL_NAME = "vision_analyze"

/**
 * 为无视觉能力的聊天模型提供识图能力：把图片交给用户配置的视觉模型分析，
 * 返回文本描述。视觉模型未配置或聊天模型本身支持图片时不注册本工具。
 */
fun createVisionTool(
    visionModel: Model,
    provider: ProviderSetting,
    providerManager: ProviderManager,
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    filesManager: FilesManager,
    context: Context,
): Tool = Tool(
    name = VISION_TOOL_NAME,
    description = """
        Analyze an image with a dedicated vision model — you cannot see images yourself.
        Use it when the user sends an image, references an image file, or you need to inspect
        an image in the workspace (screenshot, photo, chart, etc.).
        Returns the vision model's textual description of the image.
    """.trimIndent(),
    systemPrompt = { _, _ ->
        "This model has no vision capability: image parts in the conversation are not visible to you. " +
            "Whenever a question involves an image, call the $VISION_TOOL_NAME tool with the image " +
            "(URL, absolute file path, or workspace path) and answer based on its textual result."
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("image", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Image source: http(s) URL, absolute file path, or workspace Rootfs path (e.g. /workspace/screenshot.png)"
                    )
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "What to look for or do with the image. Defaults to a full description"
                    )
                })
            },
            required = listOf("image"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val image = obj["image"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (image.isBlank()) {
            error("$VISION_TOOL_NAME requires an image (URL, file path, or workspace path)")
        }
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

        val bytes = resolveImageBytes(
            source = image,
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            privateRoots = listOf(context.filesDir, context.cacheDir).map { it.canonicalFile.path },
        )
        val uri = filesManager.createChatFilesByByteArrays(listOf(bytes)).first()
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Image(url = uri.toString()),
                UIMessagePart.Text(prompt.ifBlank { "Describe this image in detail." }),
            ),
        )
        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(message),
            params = backgroundTextGenerationParams(visionModel),
        )
        val answer = result.message.toText().trim()
        if (answer.isBlank()) {
            error("Vision model returned an empty response")
        }
        Log.i(TAG, "vision_analyze: analyzed $image (${bytes.size} bytes)")
        listOf(
            UIMessagePart.Text(
                JsonInstantPretty.encodeToString(
                    buildJsonObject {
                        put("image", image)
                        put("result", answer)
                    }
                )
            )
        )
    },
)

private suspend fun resolveImageBytes(
    source: String,
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    privateRoots: List<String>,
): ByteArray {
    if (source.startsWith("http://") || source.startsWith("https://")) {
        return downloadImage(source)
    }
    // 宿主文件仅限应用私有目录: 免审批工具若能读任意路径, 模型可以把 rikka_hub.db
    // 等敏感文件当图片发给视觉模型(即外发到第三方 provider)
    val hostFile = if (source.startsWith("file://")) {
        source.toUri().path?.let(::File)
    } else {
        File(source)
    }
    if (hostFile != null && privateRoots.any { root ->
            hostFile.canonicalFile.path == root || hostFile.canonicalFile.path.startsWith("$root/")
        }
    ) {
        return readHostImage(hostFile)
    }
    if (workspaceId != null && source.startsWith("/")) {
        return readRootfsImage(source, workspaceId, workspaceRepository)
    }
    error("Image not found: $source")
}

private fun readHostImage(file: File): ByteArray {
    // 先查大小再读: 大文件直接 readBytes 会 OOM
    require(file.length() <= MAX_IMAGE_BYTES) {
        "Image too large: ${file.name} (${file.length() / 1024 / 1024}MB, max ${MAX_IMAGE_BYTES / 1024 / 1024}MB)"
    }
    val bytes = file.readBytes()
    require(bytes.size <= MAX_IMAGE_BYTES) {
        "Image too large: ${file.name} (${bytes.size / 1024 / 1024}MB, max ${MAX_IMAGE_BYTES / 1024 / 1024}MB)"
    }
    return bytes
}

private suspend fun readRootfsImage(
    path: String,
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
): ByteArray {
    val output = ByteArrayOutputStream()
    workspaceRepository.exportRootfsFile(workspaceId, path, output)
    val bytes = output.toByteArray()
    require(bytes.isNotEmpty()) { "Image not found in workspace: $path" }
    require(bytes.size <= MAX_IMAGE_BYTES) {
        "Image too large: $path (${bytes.size / 1024 / 1024}MB, max ${MAX_IMAGE_BYTES / 1024 / 1024}MB)"
    }
    return bytes
}

private fun downloadImage(source: String): ByteArray {
    val connection = URL(source).openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.instanceFollowRedirects = true
    try {
        val code = connection.responseCode
        require(code in 200..299) { "Image download failed: HTTP $code" }
        val declared = connection.contentLengthLong
        require(declared <= MAX_IMAGE_BYTES) {
            "Image too large: ${declared / 1024 / 1024}MB (max ${MAX_IMAGE_BYTES / 1024 / 1024}MB)"
        }
        val bytes = connection.inputStream.use { input ->
            val initialCapacity = if (declared > 0) declared.toInt() else 1024 * 1024
            val buffer = ByteArrayOutputStream(initialCapacity)
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                require(buffer.size() + read <= MAX_IMAGE_BYTES) {
                    "Image too large (max ${MAX_IMAGE_BYTES / 1024 / 1024}MB)"
                }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
        require(bytes.isNotEmpty()) { "Image download returned empty content" }
        return bytes
    } finally {
        connection.disconnect()
    }
}