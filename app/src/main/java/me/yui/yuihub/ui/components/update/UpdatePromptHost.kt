package me.yui.yuihub.ui.components.update

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import kotlinx.coroutines.delay
import me.yui.yuihub.R
import me.yui.yuihub.data.update.AppUpdateInfo
import me.yui.yuihub.data.update.UpdateChecker
import me.yui.yuihub.data.update.UpdateDownloader
import me.yui.yuihub.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject

private const val PREF_IGNORED_VERSION = "update_ignored_version"
private const val STARTUP_CHECK_DELAY_MS = 4_000L

/**
 * 启动更新提示：延迟数秒检查新版本，非用户忽略的版本则弹窗提醒。
 *
 * 弹窗在下载期间保持打开并实时展示「测速 → 下载进度 → 完成/失败」，
 * 状态来自 [UpdateDownloader] 单例，即使中途退到后台再回来也一致。
 */
@Composable
fun UpdatePromptHost() {
    val context = LocalContext.current
    val checker = koinInject<UpdateChecker>()
    val downloader = koinInject<UpdateDownloader>()
    val toaster = LocalToaster.current
    var pendingUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        // 等首帧稳定后再检查，避免抢占启动性能
        delay(STARTUP_CHECK_DELAY_MS)
        val result = runCatching { checker.check() }.getOrNull() ?: return@LaunchedEffect
        if (result is UpdateChecker.CheckResult.UpdateAvailable) {
            val prefs = context.getSharedPreferences("yuihub.preferences", Context.MODE_PRIVATE)
            val ignoredVersion = prefs.getString(PREF_IGNORED_VERSION, null)
            if (result.update.versionName != ignoredVersion) {
                pendingUpdate = result.update
            }
        }
    }

    pendingUpdate?.let { update ->
        val downloadState by downloader.state.collectAsStateWithLifecycle()

        UpdateDialog(
            update = update,
            downloadState = downloadState,
            onDismiss = {
                // 下载中关闭 = 取消下载（对话框文案已说明）；已完成/失败时只是关窗，
                // 不能取消，否则会把已下载好的安装包从系统下载记录里删掉
                if (downloadState is UpdateDownloader.State.TestingRoutes ||
                    downloadState is UpdateDownloader.State.Downloading
                ) {
                    downloader.cancel()
                }
                pendingUpdate = null
            },
            onIgnore = {
                context.getSharedPreferences("yuihub.preferences", Context.MODE_PRIVATE)
                    .edit { putString(PREF_IGNORED_VERSION, update.versionName) }
                pendingUpdate = null
            },
            onUpdate = { info -> downloader.start(info) },
            onRetry = { downloader.retry() },
            onInstall = {
                when (downloader.install(context)) {
                    UpdateDownloader.InstallOutcome.LAUNCHED -> pendingUpdate = null
                    UpdateDownloader.InstallOutcome.NEED_PERMISSION -> toaster.show(
                        context.getString(R.string.update_dialog_install_permission_needed),
                        type = ToastType.Warning,
                    )
                    // 安装包被清理（如用户在通知栏清掉了下载）时提示重新下载
                    UpdateDownloader.InstallOutcome.UNAVAILABLE -> toaster.show(
                        context.getString(R.string.update_dialog_install_unavailable),
                        type = ToastType.Error,
                    )
                }
            },
        )
    }
}
