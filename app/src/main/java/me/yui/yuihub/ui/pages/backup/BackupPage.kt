package me.yui.yuihub.ui.pages.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArchiveRestore
import me.rerere.hugeicons.stroke.BookOpenText
import me.rerere.hugeicons.stroke.DatabaseBackup
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileUpload
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Tools
import me.yui.yuihub.R
import me.yui.yuihub.data.sync.BackupInspection
import me.yui.yuihub.data.sync.BackupItem
import me.yui.yuihub.data.sync.BackupSizeSummary
import me.yui.yuihub.data.sync.LocalBackupFile
import me.yui.yuihub.ui.components.nav.BackButton
import me.yui.yuihub.ui.context.LocalToaster
import me.yui.yuihub.ui.pages.backup.components.BackupDialog
import me.yui.yuihub.ui.theme.CustomColors
import me.yui.yuihub.utils.fileSizeToString
import me.yui.yuihub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class BackupTab(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    BACKUP(R.string.backup_page_tab_backup, HugeIcons.DatabaseBackup),
    RESTORE(R.string.backup_page_tab_restore, HugeIcons.ArchiveRestore),
    AUTO(R.string.backup_page_tab_auto, HugeIcons.Settings02),
}

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    var showRestartDialog by remember { mutableStateOf(false) }
    var tabName by rememberSaveable { mutableStateOf(BackupTab.BACKUP.name) }
    val tab = BackupTab.entries.firstOrNull { it.name == tabName } ?: BackupTab.BACKUP

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            BackupTabBar(
                selected = tab,
                onSelect = { tabName = it.name },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (fadeIn() + slideInHorizontally { direction * it / 16 })
                        .togetherWith(fadeOut() + slideOutHorizontally { -direction * it / 16 })
                },
                label = "backupTab",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    BackupTab.BACKUP -> BackupContentTab(vm)
                    BackupTab.RESTORE -> RestoreTab(vm, onShowRestartDialog = { showRestartDialog = true })
                    BackupTab.AUTO -> AutoBackupTab(vm)
                }
            }
        }
    }

    if (showRestartDialog) BackupDialog()
}

@Composable
private fun BackupTabBar(
    selected: BackupTab,
    onSelect: (BackupTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BackupTab.entries.forEach { tab ->
            val active = tab == selected
            val container by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "backupTabContainer",
            )
            val content by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "backupTabContent",
            )
            Surface(
                onClick = { onSelect(tab) },
                shape = CircleShape,
                color = container,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = content)
                    Text(
                        text = stringResource(tab.titleRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupContentTab(vm: BackupVM) {
    val selected by vm.localBackupItems.collectAsStateWithLifecycle()
    val sizes by vm.sizes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isExporting = true
            var exportFile: File? = null
            try {
                val prepared = vm.exportToFile()
                exportFile = prepared
                withContext(Dispatchers.IO) {
                    val jobContext = currentCoroutineContext()
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(prepared).use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                jobContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Cannot open backup destination")
                }
                toaster.show(resources.getString(R.string.backup_page_backup_success), type = ToastType.Success)
                vm.refreshLocalState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show(
                    resources.getString(R.string.backup_page_export_failed, e.message ?: ""),
                    type = ToastType.Error,
                )
            } finally {
                exportFile?.delete()
                isExporting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BackupSummaryCard(
            selectedCount = selected.size,
            totalBytes = sizes.total(selected),
            onSelectAll = { vm.updateLocalBackupItems(BackupItem.ALL) },
            onClear = { vm.updateLocalBackupItems(emptyList()) },
        )

        Text(
            text = stringResource(R.string.backup_page_data_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        BackupItemCard(
            item = BackupItem.DATABASE,
            selected = BackupItem.DATABASE in selected,
            bytes = sizes.bytesOf(BackupItem.DATABASE),
            onToggle = { vm.toggleLocalBackupItem(BackupItem.DATABASE) },
        )
        BackupItemCard(
            item = BackupItem.SETTINGS,
            selected = BackupItem.SETTINGS in selected,
            bytes = sizes.bytesOf(BackupItem.SETTINGS),
            onToggle = { vm.toggleLocalBackupItem(BackupItem.SETTINGS) },
        )

        Text(
            text = stringResource(R.string.backup_page_files_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        BackupItem.FILE_ITEMS.forEach { item ->
            BackupItemCard(
                item = item,
                selected = item in selected,
                bytes = sizes.bytesOf(item),
                onToggle = { vm.toggleLocalBackupItem(item) },
            )
        }

        WorkspaceExcludedCard()

        Button(
            onClick = {
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                createDocumentLauncher.launch("yuihub_backup_$timestamp.zip")
            },
            enabled = !isExporting && selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isExporting) {
                CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Icon(HugeIcons.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = stringResource(
                    if (isExporting) R.string.backup_page_exporting else R.string.backup_page_export_now,
                    formatBytes(sizes.total(selected)),
                ),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun BackupSummaryCard(
    selectedCount: Int,
    totalBytes: Long,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.backup_page_selected_items, selectedCount),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatBytes(totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSelectAll) {
                    Text(stringResource(R.string.backup_page_select_all))
                }
                TextButton(onClick = onClear, enabled = selectedCount > 0) {
                    Text(stringResource(R.string.backup_page_clear_selection))
                }
            }
            Text(
                text = stringResource(R.string.backup_page_scope_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackupItemCard(
    item: BackupItem,
    selected: Boolean,
    bytes: Long,
    onToggle: () -> Unit,
) {
    val title = stringResource(itemTitle(item))
    val description = stringResource(itemDescription(item))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(itemIcon(item), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatBytes(bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun WorkspaceExcludedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Folder01,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_page_workspace_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.backup_page_workspace_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun RestoreTab(vm: BackupVM, onShowRestartDialog: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val localFiles by vm.localFiles.collectAsStateWithLifecycle()
    var isBusy by remember { mutableStateOf(false) }
    var pendingInspection by remember { mutableStateOf<BackupInspection?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingIsTemp by remember { mutableStateOf(false) }
    var showRikkaHubDialog by remember { mutableStateOf(false) }

    fun inspectAndPreview(file: File) {
        scope.launch {
            isBusy = true
            try {
                pendingInspection = vm.inspectBackup(file)
                pendingFile = file
                pendingIsTemp = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show(
                    resources.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                    type = ToastType.Error,
                )
            } finally {
                isBusy = false
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            val temp = File(context.cacheDir, "backup_inspect_${System.currentTimeMillis()}.zip")
            try {
                withContext(Dispatchers.IO) {
                    val jobContext = currentCoroutineContext()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                jobContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= 8L * 1024 * 1024 * 1024) { "Backup archive is too large" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Cannot open backup source")
                }
                pendingInspection = vm.inspectBackup(temp)
                pendingFile = temp
                pendingIsTemp = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                temp.delete()
                toaster.show(
                    resources.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                    type = ToastType.Error,
                )
            } finally {
                isBusy = false
            }
        }
    }

    val rikkaHubLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            val temp = File(context.cacheDir, "rikkahub_import_${System.currentTimeMillis()}.zip")
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    } ?: error("Cannot open backup source")
                }
                val result = vm.importRikkaHubBackup(temp)
                val suffix = if (result.settingsImported) "" else "\n" + resources.getString(R.string.backup_page_rikkahub_settings_failed)
                toaster.show(
                    resources.getString(R.string.backup_page_rikkahub_import_success, result.totalRows) + suffix,
                    type = if (result.settingsImported && result.failedTables.isEmpty()) ToastType.Success else ToastType.Warning,
                )
                if (result.totalRows > 0 || result.settingsImported) onShowRestartDialog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show(
                    resources.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                    type = ToastType.Error,
                )
            } finally {
                temp.delete()
                isBusy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_page_restore_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.backup_page_restore_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.backup_page_safety_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { openDocumentLauncher.launch(arrayOf("application/zip")) },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isBusy) CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                        else Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.backup_page_choose_backup), modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = { showRikkaHubDialog = true }, enabled = !isBusy) {
                        Text(stringResource(R.string.backup_page_rikkahub_import))
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.backup_page_local_backups),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        if (localFiles.isEmpty()) {
            Text(
                text = stringResource(R.string.backup_page_no_local_backups),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            localFiles.forEach { backup ->
                LocalBackupRow(
                    backup = backup,
                    enabled = !isBusy,
                    onRestore = { inspectAndPreview(backup.file) },
                )
            }
        }
    }

    pendingInspection?.let { inspection ->
        RestorePreviewSheet(
            inspection = inspection,
            onDismiss = {
                pendingInspection = null
                if (pendingIsTemp) pendingFile?.delete()
                pendingFile = null
                pendingIsTemp = false
            },
            onConfirm = { items ->
                val file = pendingFile ?: return@RestorePreviewSheet
                scope.launch {
                    isBusy = true
                    try {
                        when (val outcome = vm.restoreFromLocalFile(file, items)) {
                            RestoreOutcome.LocalRestored -> {
                                toaster.show(resources.getString(R.string.backup_page_restore_success), type = ToastType.Success)
                                onShowRestartDialog()
                            }
                            is RestoreOutcome.RikkaHubImported -> {
                                toaster.show(
                                    resources.getString(
                                        R.string.backup_page_rikkahub_import_success,
                                        outcome.result.totalRows,
                                    ),
                                    type = ToastType.Success,
                                )
                                onShowRestartDialog()
                            }
                        }
                        pendingInspection = null
                        if (pendingIsTemp) pendingFile?.delete()
                        pendingFile = null
                        pendingIsTemp = false
                        vm.refreshLocalState()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toaster.show(
                            resources.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                            type = ToastType.Error,
                        )
                    } finally {
                        isBusy = false
                    }
                }
            },
        )
    }

    if (showRikkaHubDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRikkaHubDialog = false },
            title = { Text(stringResource(R.string.backup_page_rikkahub_import)) },
            text = { Text(stringResource(R.string.backup_page_rikkahub_import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showRikkaHubDialog = false
                    rikkaHubLauncher.launch(arrayOf("application/zip"))
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRikkaHubDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LocalBackupRow(
    backup: LocalBackupFile,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.groupedCardSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.FileZip, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.file.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                if (backup.kind == LocalBackupFile.Kind.AUTO) {
                                    R.string.backup_page_auto_backup
                                } else {
                                    R.string.backup_page_safety_backup
                                }
                            )
                        )
                        append(" · ")
                        append(Instant.ofEpochMilli(backup.createdAt).toLocalDateTime())
                        append(" · ")
                        append(backup.file.length().fileSizeToString())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRestore, enabled = enabled) {
                Text(stringResource(R.string.backup_page_restore))
            }
        }
    }
}

@Composable
private fun RestorePreviewSheet(
    inspection: BackupInspection,
    onDismiss: () -> Unit,
    onConfirm: (Collection<BackupItem>) -> Unit,
) {
    var selected by remember(inspection) { mutableStateOf(inspection.presentItems) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.backup_page_restore_preview),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = buildString {
                    append(inspection.file.name)
                    append(" · ")
                    append(inspection.archiveBytes.fileSizeToString())
                    append(" · ")
                    append(Instant.ofEpochMilli(inspection.createdAt).toLocalDateTime())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inspection.isLegacy) {
                Text(
                    text = stringResource(R.string.backup_page_legacy_archive),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inspection.counts.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.backup_page_counts,
                        inspection.counts["conversations"] ?: 0,
                        inspection.counts["message_nodes"] ?: 0,
                        inspection.counts["memories"] ?: 0,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.backup_page_restore_choose),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 2.dp),
            )
            inspection.presentItems.forEach { item ->
                BackupItemCard(
                    item = item,
                    selected = item in selected,
                    bytes = when (item) {
                        BackupItem.DATABASE -> inspection.manifest?.databaseBytes ?: 0L
                        BackupItem.SETTINGS -> inspection.manifest?.settingsBytes ?: 0L
                        else -> inspection.itemBytes[item] ?: 0L
                    },
                    onToggle = {
                        selected = if (item in selected) selected - item else selected + item
                    },
                )
            }
            Text(
                text = stringResource(R.string.backup_page_restore_overwrite),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_page_restore_confirm))
            }
        }
    }
}

@Composable
private fun AutoBackupTab(vm: BackupVM) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 20.dp),
    ) {
        me.yui.yuihub.ui.pages.backup.tabs.ReminderTab(vm)
    }
}

@Composable
private fun formatBytes(bytes: Long): String = if (bytes <= 0) "—" else bytes.fileSizeToString()

@StringRes
private fun itemTitle(item: BackupItem): Int = when (item) {
    BackupItem.DATABASE -> R.string.backup_page_data
    BackupItem.SETTINGS -> R.string.backup_page_settings
    BackupItem.UPLOAD -> R.string.backup_page_upload
    BackupItem.IMAGES -> R.string.backup_page_images
    BackupItem.SKILLS -> R.string.backup_page_skills
    BackupItem.FONTS -> R.string.backup_page_fonts
    BackupItem.TOOL_OUTPUTS -> R.string.backup_page_tool_outputs
}

@StringRes
private fun itemDescription(item: BackupItem): Int = when (item) {
    BackupItem.DATABASE -> R.string.backup_page_data_desc
    BackupItem.SETTINGS -> R.string.backup_page_settings_desc
    BackupItem.UPLOAD -> R.string.backup_page_upload_desc
    BackupItem.IMAGES -> R.string.backup_page_images_desc
    BackupItem.SKILLS -> R.string.backup_page_skills_desc
    BackupItem.FONTS -> R.string.backup_page_fonts_desc
    BackupItem.TOOL_OUTPUTS -> R.string.backup_page_tool_outputs_desc
}

private fun itemIcon(item: BackupItem): ImageVector = when (item) {
    BackupItem.DATABASE -> HugeIcons.DatabaseBackup
    BackupItem.SETTINGS -> HugeIcons.Settings02
    BackupItem.UPLOAD -> HugeIcons.FileUpload
    BackupItem.IMAGES -> HugeIcons.Image02
    BackupItem.SKILLS -> HugeIcons.Puzzle
    BackupItem.FONTS -> HugeIcons.BookOpenText
    BackupItem.TOOL_OUTPUTS -> HugeIcons.Tools
}
