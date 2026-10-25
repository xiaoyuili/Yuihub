package me.yui.yuihub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import com.dokar.sonner.ToastType
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.files.FilesManager
import me.yui.yuihub.ui.components.ui.permission.PermissionCamera
import me.yui.yuihub.ui.components.ui.permission.PermissionManager
import me.yui.yuihub.ui.components.ui.permission.rememberPermissionState
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.hooks.ChatInputState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

internal data class ChatAttachmentPickerActions(
    val onTakePicture: () -> Unit,
    val onPickImage: () -> Unit,
    val onPickVideo: () -> Unit,
    val onPickAudio: () -> Unit,
    val onPickFile: () -> Unit,
)

@Composable
internal fun rememberChatAttachmentPickerActions(
    inputState: ChatInputState,
    setting: Settings,
    onAttachmentAdded: () -> Unit,
): ChatAttachmentPickerActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val launchCameraCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onAttachmentAdded()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                val pendingUri = cameraOutputUri
                if (pendingUri != null) {
                    scope.launch {
                        inputState.addImages(filesManager.createChatFilesByContents(listOf(pendingUri)))
                        cameraOutputFile?.delete()
                        cameraOutputFile = null
                        cameraOutputUri = null
                        onAttachmentAdded()
                    }
                }
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onTakePicture: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    val launchImageCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            onAttachmentAdded()
        },
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    scope.launch {
                        inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                        onAttachmentAdded()
                    }
                } else if (selectedUris.size == 1) {
                    launchImageCrop(selectedUris.first())
                } else {
                    scope.launch {
                        inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                        onAttachmentAdded()
                    }
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                    onAttachmentAdded()
                }
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                    onAttachmentAdded()
                }
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    // agent 客户端接受任意文件类型，不再按白名单过滤
                    val documents = uris.mapNotNull { uri ->
                        val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                        val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    resources.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    }
                    if (documents.isNotEmpty()) {
                        inputState.addFiles(documents)
                        onAttachmentAdded()
                    }
                }
            }
        }

    return ChatAttachmentPickerActions(
        onTakePicture = onTakePicture,
        onPickImage = {
            imagePickerLauncher.launch("image/*")
        },
        onPickVideo = { videoPickerLauncher.launch("video/*") },
        onPickAudio = { audioPickerLauncher.launch("audio/*") },
        onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
    )
}
