package me.yui.yuihub.ui.components.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.NewReleases
import me.yui.yuihub.R
import me.yui.yuihub.data.update.AppUpdateInfo
import me.yui.yuihub.data.update.UpdateDownloader
import me.yui.yuihub.utils.fileSizeToString

/**
 * 更新弹窗。下载阶段保持打开并实时展示进展：
 * - 测速中：转圈 + 「正在测试下载线路…」
 * - 下载中：进度条 + 百分比 + 已下载/总大小
 * - 已完成：提示可安装，主按钮变为「安装」
 * - 失败：展示原因，主按钮变为「重试」
 * 关闭（取消）在下载中同样可见，会同步取消下载。
 */
@Composable
fun UpdateDialog(
    update: AppUpdateInfo,
    downloadState: UpdateDownloader.State,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
    onUpdate: (AppUpdateInfo) -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
) {
    val downloading = downloadState is UpdateDownloader.State.TestingRoutes ||
        downloadState is UpdateDownloader.State.Downloading
    val completed = downloadState is UpdateDownloader.State.Completed
    val failed = downloadState is UpdateDownloader.State.Failed

    AlertDialog(
        // 下载/测速过程中禁止点外部关闭，避免误触后不知道下载仍在进行
        onDismissRequest = { if (!downloading) onDismiss() },
        icon = {
            Surface(
                shape = CircleShape,
                color = when {
                    completed -> MaterialTheme.colorScheme.primaryContainer
                    failed -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Icon(
                    imageVector = iconFor(downloadState),
                    contentDescription = null,
                    tint = when {
                        completed -> MaterialTheme.colorScheme.onPrimaryContainer
                        failed -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier
                        .padding(10.dp)
                        .size(28.dp),
                )
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when {
                        completed -> stringResource(R.string.update_dialog_ready_to_install)
                        failed -> stringResource(R.string.update_dialog_download_failed_title)
                        else -> stringResource(R.string.update_dialog_new_version_found)
                    }
                )
                Text(
                    text = "v${update.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.update_dialog_release_notes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = update.releaseNotes.ifBlank {
                                stringResource(R.string.update_dialog_no_release_notes)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // 下载进展：仅在有实际状态时显示，避免干扰纯阅读更新日志
                when (downloadState) {
                    UpdateDownloader.State.Idle -> Unit

                    UpdateDownloader.State.TestingRoutes -> {
                        DownloadStatusRow(
                            text = stringResource(R.string.update_dialog_preparing_download),
                            indeterminate = true,
                        )
                    }

                    is UpdateDownloader.State.Downloading -> {
                        val progress = downloadState.progress
                        DownloadStatusRow(
                            text = if (progress != null) {
                                stringResource(
                                    R.string.update_dialog_downloading,
                                    (progress * 100).toInt(),
                                    downloadState.downloadedBytes.fileSizeToString(),
                                    downloadState.totalBytes.fileSizeToString(),
                                )
                            } else {
                                stringResource(
                                    R.string.update_dialog_downloading_unknown_total,
                                    downloadState.downloadedBytes.fileSizeToString(),
                                )
                            },
                            progress = progress,
                        )
                    }

                    UpdateDownloader.State.Completed -> {
                        DownloadStatusRow(
                            text = stringResource(R.string.update_dialog_download_completed),
                            done = true,
                        )
                    }

                    is UpdateDownloader.State.Failed -> {
                        Text(
                            text = downloadState.reason
                                ?.let { stringResource(R.string.update_dialog_download_failed_reason, it) }
                                ?: stringResource(R.string.update_dialog_download_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        dismissButton = {
            if (downloading) {
                // 下载中：只留一个取消，点了会同时取消下载
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                Row {
                    if (!completed && !failed) {
                        TextButton(onClick = onIgnore) {
                            Text(stringResource(R.string.update_dialog_ignore))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(if (completed) R.string.update_dialog_later else R.string.cancel))
                    }
                }
            }
        },
        confirmButton = {
            when {
                completed -> TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.update_dialog_install))
                }

                failed -> TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.update_dialog_retry))
                }

                downloading -> Unit

                else -> TextButton(onClick = { onUpdate(update) }) {
                    Text(stringResource(R.string.update_dialog_update))
                }
            }
        },
    )
}

private fun iconFor(state: UpdateDownloader.State): ImageVector = when (state) {
    is UpdateDownloader.State.Completed -> HugeIcons.CheckmarkCircle02
    is UpdateDownloader.State.Failed -> HugeIcons.AlertCircle
    else -> HugeIcons.NewReleases
}

/** 状态行：进度条 + 说明文字。不确定进度（测速/未知总大小）用转圈代替进度条 */
@Composable
private fun DownloadStatusRow(
    text: String,
    progress: Float? = null,
    indeterminate: Boolean = false,
    done: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (indeterminate || (progress == null && !done)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            } else if (done) {
                Icon(
                    imageVector = HugeIcons.CheckmarkCircle02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (!indeterminate && !done) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
