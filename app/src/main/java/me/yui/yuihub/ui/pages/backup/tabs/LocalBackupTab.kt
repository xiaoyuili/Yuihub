package me.yui.yuihub.ui.pages.backup.tabs

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.yui.yuihub.R
import me.yui.yuihub.data.sync.BackupItem
import me.yui.yuihub.ui.components.ui.CardGroup
import me.yui.yuihub.ui.components.ui.StickyHeader
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.pages.backup.BackupVM
import me.yui.yuihub.ui.pages.backup.RestoreOutcome
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LocalBackupTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selectedBackupItems by vm.localBackupItems.collectAsStateWithLifecycle()
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var showRikkaHubImportDialog by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    val exportFile = vm.exportToFile()

                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    exportFile.delete()

                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    val tempFile =
                        File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")

                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    when (val outcome = vm.restoreFromLocalFile(tempFile)) {
                        is RestoreOutcome.LocalRestored -> {
                            toaster.show(
                                context.getString(R.string.backup_page_restore_success),
                                type = ToastType.Success
                            )
                            onShowRestartDialog()
                        }

                        // 选到的是 RikkaHub 备份：已自动改走「按列名交集」导入，
                        // 不替换数据库文件，因此不会触发上游库与迁移链不兼容的问题
                        is RestoreOutcome.RikkaHubImported -> {
                            toaster.show(
                                context.getString(
                                    R.string.backup_page_rikkahub_import_success,
                                    outcome.result.totalRows,
                                ),
                                type = ToastType.Success
                            )
                            if (outcome.result.totalRows > 0) onShowRestartDialog()
                        }
                    }

                    tempFile.delete()
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isRestoring = false
            }
        }
    }

    // RikkaHub（上游）备份：格式同源但表结构不同，需走「按列名交集」的专用导入
    val rikkaHubImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    val tempFile =
                        File(context.cacheDir, "temp_rikkahub_${System.currentTimeMillis()}.zip")

                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    val result = vm.importRikkaHubBackup(tempFile)
                    tempFile.delete()

                    toaster.show(
                        context.getString(
                            R.string.backup_page_rikkahub_import_success,
                            result.totalRows,
                        ),
                        type = ToastType.Success
                    )
                    if (result.totalRows > 0) onShowRestartDialog()
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isRestoring = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StickyHeader {
            Text(stringResource(R.string.backup_page_local_backup_export))
        }

        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                supportingContent = {
                    Column {
                        MultiChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BackupItem.entries.forEachIndexed { index, item ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = BackupItem.entries.size
                                    ),
                                    onCheckedChange = { checked ->
                                        val newItems = if (checked) {
                                            selectedBackupItems + item
                                        } else {
                                            selectedBackupItems - item
                                        }
                                        vm.updateLocalBackupItems(newItems)
                                    },
                                    checked = item in selectedBackupItems
                                ) {
                                    Text(
                                        when (item) {
                                            BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                                            BackupItem.FILES -> stringResource(R.string.backup_page_files)
                                            BackupItem.SETTINGS -> stringResource(R.string.backup_page_settings)
                                        }
                                    )
                                }
                            }
                        }
                        // 工作区（rootfs 与 /workspace 文件）不在备份范围内，而 WorkspaceEntity 随数据库恢复，
                        // 不说明会让人误以为恢复后工作区文件还在
                        Text(
                            text = stringResource(R.string.backup_page_scope_note),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
            )
            item(
                onClick = if (!isExporting) {
                    {
                        val timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        createDocumentLauncher.launch("yuihub_backup_$timestamp.zip")
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                supportingContent = {
                    Text(
                        if (isExporting) {
                            stringResource(R.string.backup_page_exporting)
                        } else {
                            stringResource(R.string.backup_page_export_desc)
                        }
                    )
                },
                leadingContent = {
                    if (isExporting) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(HugeIcons.File01, null)
                    }
                },
            )

            item(
                onClick = if (!isRestoring) {
                    {
                        showImportConfirmDialog = true
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                supportingContent = {
                    Text(
                        if (isRestoring) {
                            stringResource(R.string.backup_page_importing)
                        } else {
                            stringResource(R.string.backup_page_import_desc)
                        }
                    )
                },
                leadingContent = {
                    if (isRestoring) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(HugeIcons.FileImport, null)
                    }
                },
            )

            // 上游 RikkaHub 备份：格式同源但表结构不同，走专用导入
            item(
                onClick = if (!isRestoring) {
                    {
                        showRikkaHubImportDialog = true
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_rikkahub_import)) },
                supportingContent = { Text(stringResource(R.string.backup_page_rikkahub_import_desc)) },
                leadingContent = {
                    Icon(HugeIcons.FileImport, null)
                },
            )
        }
    }

    if (showRikkaHubImportDialog) {
        AlertDialog(
            onDismissRequest = { showRikkaHubImportDialog = false },
            title = { Text(stringResource(R.string.backup_page_rikkahub_import)) },
            text = { Text(stringResource(R.string.backup_page_rikkahub_import_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRikkaHubImportDialog = false
                        rikkaHubImportLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRikkaHubImportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
