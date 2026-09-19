package me.yui.yuihub.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toFile
import com.dokar.sonner.ToastType
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.yui.yuihub.ui.context.LocalToaster
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: suspend (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
    aspectRatio: Pair<Float, Float>? = null,
    freeStyleCropEnabled: Boolean = true
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val croppedUri = cropOutputUri
                if (croppedUri != null) {
                    // 消费方需要异步拷贝裁剪产物, 必须等它完成再删临时文件:
                    // 此回调返回后 Compose 会立刻继续处理, 若先删文件, 消费方的
                    // 协程读到的是已删除的文件, 头像/附件会静默丢失
                    scope.launch {
                        try {
                            onCroppedImageReady(croppedUri)
                        } finally {
                            croppedUri.toFile()?.delete()
                            onCleanup?.invoke()
                        }
                    }
                } else {
                    onCleanup?.invoke()
                }
            }

            UCrop.RESULT_ERROR -> {
                val error = result.data?.let { UCrop.getError(it) }
                Logging.log(
                    "CropLauncher",
                    "crop failed: ${error?.message} | ${error?.stackTraceToString()}"
                )
                toaster.show(
                    "Failed to crop image: ${error?.message ?: "unknown error"}",
                    type = ToastType.Error
                )
                cropOutputUri?.toFile()?.delete()
                onCleanup?.invoke()
            }

            else -> {
                // 用户取消裁剪
                cropOutputUri?.toFile()?.delete()
                onCleanup?.invoke()
            }
        }
        cropOutputUri = null
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
        cropOutputUri = Uri.fromFile(outputFile)

        var crop = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(freeStyleCropEnabled)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            // Chat attachments are written to a .jpg output file. PNG encoding is
            // particularly expensive for large camera/gallery images and provides
            // no benefit for this flow, so use JPEG to keep confirmation responsive.
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
        }).withMaxResultSize(4096, 4096)
        aspectRatio?.let { (x, y) ->
            crop = crop.withAspectRatio(x, y)
        }
        val cropIntent = crop.getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}
