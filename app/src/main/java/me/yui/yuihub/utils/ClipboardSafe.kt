package me.yui.yuihub.utils

import android.content.ClipData
import android.util.Log
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CancellationException

private const val TAG = "ClipboardSafe"

/** 单个 clip 载荷上限（Binder 事务约 1MB，留出余量给包裹数据）。 */
private const val MAX_CLIP_BYTES = 400_000

/** 复制结果：成功、因过大被拒、或平台抛错 */
enum class SafeCopyResult { COPIED, TOO_LARGE, FAILED }

/**
 * 安全写剪贴板。
 *
 * 直接把超长文本交给 [Clipboard.setClipEntry] 会因 Binder 事务过大（TransactionTooLarge）
 * 抛 RuntimeException 导致闪退；代码块、表格这类内容长度完全由用户数据决定，
 * 因此写入前做长度判定，并在写入失败时兜底捕获，绝不把异常抛到 UI 线程。
 */
suspend fun Clipboard.setClipEntrySafely(label: String, text: String): SafeCopyResult {
    if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIP_BYTES) {
        return SafeCopyResult.TOO_LARGE
    }
    return try {
        setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
        SafeCopyResult.COPIED
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 不同 ROM 对事务上限的处理不一致，捕获异常避免闪退
        Log.w(TAG, "setClipEntry failed (len=${text.length})", e)
        SafeCopyResult.FAILED
    }
}
