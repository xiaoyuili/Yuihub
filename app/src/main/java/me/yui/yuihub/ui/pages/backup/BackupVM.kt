package me.yui.yuihub.ui.pages.backup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.sync.BackupInspection
import me.yui.yuihub.data.sync.BackupItem
import me.yui.yuihub.data.sync.BackupSizeSummary
import me.yui.yuihub.data.sync.LocalBackupFile
import me.yui.yuihub.data.sync.LocalBackupService
import me.yui.yuihub.data.sync.RikkaHubImporter
import me.yui.yuihub.worker.AutoBackupScheduler
import java.io.File

class BackupVM(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val localBackup: LocalBackupService,
    private val rikkaHubImporter: RikkaHubImporter,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val localBackupItems = MutableStateFlow(BackupItem.ALL.toSet())
    val sizes = MutableStateFlow(BackupSizeSummary())
    val localFiles = MutableStateFlow<List<LocalBackupFile>>(emptyList())

    init {
        refreshLocalState()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch { settingsStore.update(settings) }
    }

    fun updateLocalBackupItems(items: Collection<BackupItem>) {
        localBackupItems.value = items.toSet()
    }

    fun toggleLocalBackupItem(item: BackupItem) {
        localBackupItems.value = localBackupItems.value.let {
            if (item in it) it - item else it + item
        }
    }

    fun refreshLocalState() {
        viewModelScope.launch {
            sizes.value = runCatching { localBackup.calculateSizes() }.getOrDefault(BackupSizeSummary())
            localFiles.value = runCatching { localBackup.listLocalBackups() }.getOrDefault(emptyList())
        }
    }

    suspend fun exportToFile(): File {
        val file = localBackup.prepareBackupFile(localBackupItems.value)
        recordBackupTime()
        refreshLocalState()
        return file
    }

    suspend fun inspectBackup(file: File): BackupInspection = localBackup.inspectBackup(file)

    suspend fun restoreFromLocalFile(file: File, items: Collection<BackupItem>): RestoreOutcome {
        if (isRikkaHubBackup(file)) {
            return RestoreOutcome.RikkaHubImported(importRikkaHubBackup(file, items))
        }
        localBackup.restoreFromLocalFile(file, items)
        refreshLocalState()
        return RestoreOutcome.LocalRestored
    }

    suspend fun createSafetyBackup(): File {
        val file = localBackup.createSafetyBackup()
        refreshLocalState()
        return file
    }

    suspend fun isRikkaHubBackup(file: File): Boolean = rikkaHubImporter.looksLikeUpstreamBackup(file)

    suspend fun importRikkaHubBackup(
        file: File,
        items: Collection<BackupItem> = localBackupItems.value,
    ): RikkaHubImporter.ImportResult {
        val result = rikkaHubImporter.importFromZip(
            zipFile = file,
            includeDatabase = BackupItem.DATABASE in items,
            includeSettings = BackupItem.SETTINGS in items,
            includeFiles = items.any { it.isFile },
        )
        recordBackupTime()
        return result
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }

    fun onAutoBackupConfigChanged() {
        viewModelScope.launch {
            AutoBackupScheduler.enqueueNext(appContext, settingsStore)
        }
    }
}

sealed interface RestoreOutcome {
    data object LocalRestored : RestoreOutcome
    data class RikkaHubImported(val result: RikkaHubImporter.ImportResult) : RestoreOutcome
}
