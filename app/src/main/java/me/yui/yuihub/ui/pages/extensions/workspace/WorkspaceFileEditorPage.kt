package me.yui.yuihub.ui.pages.extensions.workspace

import android.content.Intent
import android.media.MediaPlayer
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.workspace.WorkspaceStorageArea
import me.yui.yuihub.R
import me.yui.yuihub.data.repository.WorkspaceRepository
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.components.webview.WebView
import me.yui.yuihub.ui.components.webview.rememberWebViewState
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.ui.theme.JetbrainsMono
import me.yui.yuihub.utils.SystemPermissions
import org.koin.compose.koinInject
import java.io.File

private enum class EditorMode { TEXT, IMAGE, AUDIO, VIDEO, UNSUPPORTED }

/**
 * 工作区文件详情页：按类型分流——
 * 文本可编辑（白色页面 + 黑色文字）；图片可缩放查看；音频/视频应用内播放；
 * 压缩包等二进制文件提供「用其他应用打开」。LINUX (rootfs) 区文件一律只读，避免误改系统文件。
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val mode = when (detectFileTypeByName(fileName)) {
        WorkspaceFileType.TEXT -> EditorMode.TEXT
        WorkspaceFileType.IMAGE -> EditorMode.IMAGE
        WorkspaceFileType.AUDIO -> EditorMode.AUDIO
        WorkspaceFileType.VIDEO -> EditorMode.VIDEO
        WorkspaceFileType.ARCHIVE, WorkspaceFileType.OTHER -> EditorMode.UNSUPPORTED
    }
    val supportsWebPreview = extension in setOf("html", "htm")

    val textState = rememberTextFieldState()
    var showPreview by rememberSaveable(id, area, path) { mutableStateOf(false) }
    var localPath by remember(id, area, path) { mutableStateOf<String?>(null) }
    var loading by remember(id, area, path) { mutableStateOf(true) }
    var loadError by remember(id, area, path) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(id, area, path, mode) {
        loading = true
        loadError = null
        when (mode) {
            EditorMode.TEXT -> runCatching {
                repository.readTextForPreview(id, area, path)
            }.onSuccess { content ->
                textState.setTextAndPlaceCursorAtEnd(content)
                loading = false
            }.onFailure {
                loadError = it.message ?: "读取文件失败"
                loading = false
            }

            EditorMode.UNSUPPORTED -> loading = false

            else -> runCatching {
                repository.resolveFile(id, area, path)
            }.onSuccess {
                localPath = it.absolutePath
                loading = false
            }.onFailure {
                loadError = it.message ?: "读取文件失败"
                loading = false
            }
        }
    }

    // 导出到缓存后交给系统应用打开（压缩包等不支持内嵌预览的类型）
    fun openWithSystemApp() {
        if (exporting) return
        // 安装包：先确认「安装未知应用」权限，未开启时直接引导授权，避免选完安装器却装不上
        if (extension == "apk" && !SystemPermissions.ensureInstallPermission(context)) {
            toaster.show(
                context.getString(R.string.workspace_file_editor_install_permission_needed),
                type = ToastType.Warning,
            )
            return
        }
        exporting = true
        scope.launch {
            runCatching {
                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, fileName)
                file.outputStream().use { output ->
                    repository.exportFile(id, area, path, output)
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, null))
            }.onFailure {
                toaster.show(it.message ?: "打开失败", type = ToastType.Error)
            }
            exporting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (mode == EditorMode.TEXT && supportsWebPreview && !loading && loadError == null) {
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Text(if (showPreview) "源码" else "预览")
                        }
                    }
                    if (mode == EditorMode.TEXT && editable && !loading && loadError == null) {
                        TextButton(
                            onClick = {
                                if (saving) return@TextButton
                                saving = true
                                scope.launch {
                                    runCatching {
                                        repository.writeText(
                                            id = id,
                                            path = path,
                                            text = textState.text.toString(),
                                            overwrite = true,
                                        )
                                    }.onSuccess {
                                        toaster.show("已保存", type = ToastType.Success)
                                    }.onFailure {
                                        toaster.show(it.message ?: "保存失败", type = ToastType.Error)
                                    }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                        ) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            mode == EditorMode.TEXT -> {
                if (showPreview && supportsWebPreview) {
                    WorkspaceWebPreview(
                        content = textState.text.toString(),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                } else {
                    TextEditorContent(
                        textState = textState,
                        editable = editable,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }

            mode == EditorMode.IMAGE -> localPath?.let { imagePath ->
                ZoomableImageContent(
                    imagePath = imagePath,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            mode == EditorMode.AUDIO -> localPath?.let { audioPath ->
                AudioPlayerContent(
                    filePath = audioPath,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                )
            }

            mode == EditorMode.VIDEO -> localPath?.let { videoPath ->
                VideoPlayerContent(
                    filePath = videoPath,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            else -> UnsupportedContent(
                exporting = exporting,
                onOpenWithSystemApp = { openWithSystemApp() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            )
        }
    }
}

/** 文本编辑：白色页面 + 黑色等宽文字。 */
@Composable
private fun TextEditorContent(
    textState: androidx.compose.foundation.text.input.TextFieldState,
    editable: Boolean,
    modifier: Modifier = Modifier,
) {
    TextField(
        state = textState,
        modifier = modifier
            .background(Color.White)
            .imePadding(),
        readOnly = !editable,
        lineLimits = TextFieldLineLimits.MultiLine(),
        textStyle = LocalTextStyle.current.copy(
            fontFamily = JetbrainsMono,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Color.Black,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            disabledContainerColor = Color.White,
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.Black,
            disabledTextColor = Color.Black,
            cursorColor = Color.Black,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** 图片查看：双指缩放、拖动，无保存按钮。 */
@Composable
private fun ZoomableImageContent(
    imagePath: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberZoomablePagerState { 1 }
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ImagePager(
            modifier = Modifier.fillMaxSize(),
            pagerState = state,
            imageLoader = { _ ->
                val painter = rememberAsyncImagePainter(imagePath)
                Pair(painter, painter.intrinsicSize)
            },
        )
    }
}

/** 音频播放：播放/暂停 + 进度拖动。 */
@Composable
private fun AudioPlayerContent(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(0) }
    var position by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(filePath) {
        val mediaPlayer = MediaPlayer()
        runCatching {
            mediaPlayer.setDataSource(filePath)
            mediaPlayer.prepare()
        }.onSuccess {
            player = mediaPlayer
            duration = runCatching { mediaPlayer.duration }.getOrDefault(0)
        }.onFailure {
            error = it.message ?: "无法播放该音频"
        }
        mediaPlayer.setOnCompletionListener {
            isPlaying = false
            position = 0
        }
        onDispose {
            runCatching { mediaPlayer.release() }
            player = null
        }
    }

    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(200)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = HugeIcons.MusicNote03,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        if (error != null) {
            Text(
                text = error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    val target = (fraction * duration).toInt()
                    runCatching { player?.seekTo(target) }
                    position = target
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = formatMillis(position), style = MaterialTheme.typography.labelMedium)
                IconButton(
                    onClick = {
                        val mediaPlayer = player ?: return@IconButton
                        runCatching {
                            if (isPlaying) mediaPlayer.pause() else mediaPlayer.start()
                        }
                        isPlaying = !isPlaying
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) HugeIcons.Pause else HugeIcons.Play,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Text(text = formatMillis(duration), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun formatMillis(millis: Int): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 视频播放：系统级播放控件（播放/暂停/进度/全屏）。 */
@Composable
private fun VideoPlayerContent(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    var videoView by remember { mutableStateOf<VideoView?>(null) }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoPath(filePath)
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        start()
                    }
                    videoView = this
                }
            },
        )
    }

    DisposableEffect(filePath) {
        onDispose {
            runCatching { videoView?.stopPlayback() }
            videoView = null
        }
    }
}

/** 不支持内嵌预览的类型（压缩包等）：提供系统应用打开的出口。 */
@Composable
private fun UnsupportedContent(
    exporting: Boolean,
    onOpenWithSystemApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = HugeIcons.File01,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.workspace_file_editor_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(
            onClick = onOpenWithSystemApp,
            enabled = !exporting,
        ) {
            Text(stringResource(R.string.workspace_file_editor_open_with_other))
        }
    }
}

@Composable
private fun WorkspaceWebPreview(
    content: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = content,
        baseUrl = "https://workspace-preview.invalid/",
        mimeType = "text/html",
        settings = {
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )
    WebView(state = state, modifier = modifier)
}
