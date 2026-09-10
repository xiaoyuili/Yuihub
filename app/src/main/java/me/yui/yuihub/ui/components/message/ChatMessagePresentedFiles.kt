package me.yui.yuihub.ui.components.message

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileImport
import me.yui.yuihub.Screen
import me.yui.yuihub.data.model.Assistant
import me.yui.yuihub.data.repository.WorkspaceRepository
import me.yui.yuihub.ui.context.LocalNavController
import me.yui.yuihub.utils.JsonInstant
import me.yui.yuihub.utils.fileSizeToString
import org.koin.compose.koinInject
import java.io.File

/** workspace_present_file 工具名，AI 发送文件给用户的入口。 */
const val WORKSPACE_PRESENT_FILE_TOOL = "workspace_present_file"

private data class PresentedFile(
    val path: String,
    val note: String?,
    val sizeBytes: Long?,
)

/**
 * AI 通过 workspace_present_file 发送的文件卡片列表，渲染在消息正文下方、操作按钮上方。
 * 卡片点击弹出 打开 / 导出 / 分享 操作弹层。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresentedFilesList(
    parts: List<UIMessagePart>,
    assistant: Assistant?,
) {
    val workspaceId = assistant?.workspaceId?.toString() ?: return
    val presentedFiles = remember(parts) {
        parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName == WORKSPACE_PRESENT_FILE_TOOL && it.isExecuted }
            .mapNotNull { tool ->
                val input = tool.inputAsJson() as? JsonObject ?: return@mapNotNull null
                val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val note = input["note"]?.jsonPrimitive?.contentOrNull
                val sizeBytes = tool.output.filterIsInstance<UIMessagePart.Text>()
                    .firstOrNull()
                    ?.let { part ->
                        runCatching {
                            (JsonInstant.parseToJsonElement(part.text) as? JsonObject)
                                ?.get("sizeBytes")?.jsonPrimitive?.longOrNull
                        }.getOrNull()
                    }
                PresentedFile(path = path, note = note, sizeBytes = sizeBytes)
            }
            .distinctBy { it.path }
    }
    if (presentedFiles.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        presentedFiles.forEach { file ->
            PresentedFileCard(
                path = file.path,
                note = file.note,
                sizeBytes = file.sizeBytes,
                workspaceId = workspaceId,
            )
        }
    }
}

@Composable
internal fun PresentedFileCard(
    path: String,
    note: String?,
    sizeBytes: Long?,
    workspaceId: String?,
) {
    val fileName = remember(path) { path.substringAfterLast('/').ifBlank { path } }
    var showSheet by remember(path) { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri == null || workspaceId == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val (area, relativePath) = resolveWorkspacePath(path)
                outputStream.use { output ->
                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                }
            }
        }
    }

    Surface(
        onClick = { if (workspaceId != null) showSheet = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.widthIn(min = 88.dp, max = 156.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Icon(
                imageVector = HugeIcons.FileImport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (sizeBytes != null && sizeBytes > 0) {
                Text(
                    text = sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (!note.isNullOrBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (showSheet) {
        WorkspaceFileActionSheet(
            fileName = fileName,
            subtitle = note,
            onOpen = {
                showSheet = false
                if (workspaceId != null) {
                    val (area, relativePath) = resolveWorkspacePath(path)
                    navController.navigate(
                        Screen.WorkspaceFileEditor(
                            id = workspaceId,
                            area = area.name,
                            path = relativePath,
                        )
                    )
                }
            },
            onExport = {
                exportLauncher.launch(fileName)
            },
            onShare = {
                showSheet = false
                if (workspaceId == null) return@WorkspaceFileActionSheet
                scope.launch {
                    runCatching {
                        val (area, relativePath) = resolveWorkspacePath(path)
                        val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                        val cacheFile = File(dir, fileName)
                        cacheFile.outputStream().use { output ->
                            workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cacheFile,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                }
            },
            onDismissRequest = { showSheet = false },
        )
    }
}
