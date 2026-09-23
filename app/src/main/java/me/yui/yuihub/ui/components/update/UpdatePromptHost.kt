package me.yui.yuihub.ui.components.update

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.yui.yuihub.R
import me.yui.yuihub.data.update.AppUpdateInfo
import me.yui.yuihub.data.update.UpdateChecker
import me.yui.yuihub.data.update.UpdateDownloader
import me.yui.yuihub.ui.context.LocalToaster
import org.koin.compose.koinInject

private const val PREF_IGNORED_VERSION = "update_ignored_version"
private const val STARTUP_CHECK_DELAY_MS = 4_000L

/** 测速选线并交给系统下载管理器下载，统一供启动弹窗与关于页复用。 */
@Composable
fun rememberUpdateDownloader(): (AppUpdateInfo) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val checker = koinInject<UpdateChecker>()

    return remember(context, toaster, scope, checker) {
        { update ->
            scope.launch {
                toaster.show(
                    context.getString(R.string.update_dialog_preparing_download),
                    type = ToastType.Normal,
                )
                try {
                    val downloader = UpdateDownloader(context, checker)
                    downloader.enqueueDownload(update)
                    toaster.show(
                        context.getString(R.string.update_dialog_download_started),
                        type = ToastType.Success,
                    )
                } catch (e: Exception) {
                    toaster.show(
                        context.getString(R.string.update_dialog_download_failed),
                        type = ToastType.Error,
                    )
                }
            }
        }
    }
}

/**
 * 启动更新提示：延迟数秒检查新版本，非用户忽略的版本则弹窗提醒。
 */
@Composable
fun UpdatePromptHost() {
    val context = LocalContext.current
    val checker = koinInject<UpdateChecker>()
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

    val startDownload = rememberUpdateDownloader()

    pendingUpdate?.let { update ->
        UpdateDialog(
            update = update,
            onDismiss = { pendingUpdate = null },
            onIgnore = {
                context.getSharedPreferences("yuihub.preferences", Context.MODE_PRIVATE)
                    .edit { putString(PREF_IGNORED_VERSION, update.versionName) }
                pendingUpdate = null
            },
            onUpdate = { info ->
                pendingUpdate = null
                startDownload(info)
            },
        )
    }
}
