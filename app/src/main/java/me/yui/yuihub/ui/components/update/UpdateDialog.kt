package me.yui.yuihub.ui.components.update

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.NewReleases
import me.yui.yuihub.R
import me.yui.yuihub.data.update.AppUpdateInfo

/**
 * 新版本更新弹窗：
 * 顶部升级图标 + 版本号，中间为可上下滑动的更新内容，底部左「忽略此版本」右「取消 / 更新」。
 */
@Composable
fun UpdateDialog(
    update: AppUpdateInfo,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
    onUpdate: (AppUpdateInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = HugeIcons.NewReleases,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                Text(stringResource(R.string.update_dialog_new_version_found))
                Text(
                    text = "v${update.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.update_dialog_release_notes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 4.dp))
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
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onIgnore) {
                    Text(stringResource(R.string.update_dialog_ignore))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUpdate(update) }) {
                Text(stringResource(R.string.update_dialog_update))
            }
        },
    )
}
