package me.yui.yuihub.ui.pages.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.sync.BackupItem
import me.yui.yuihub.data.sync.LocalBackupService
import me.yui.yuihub.data.sync.RikkaHubImporter
import java.io.File

class BackupVM(
    private val settingsStore: SettingsStore,
    private val localBackup: LocalBackupService,
    private val rikkaHubImporter: RikkaHubImporter,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val localBackupItems = MutableStateFlow(BackupItem.entries.toList())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateLocalBackupItems(items: List<BackupItem>) {
        localBackupItems.value = items
    }

    suspend fun exportToFile(): File {
        val file = localBackup.prepareBackupFile(localBackupItems.value)
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        localBackup.restoreFromLocalFile(file, localBackupItems.value)
    }

    /**
     * 判断该 zip 是否为 RikkaHub（上游）备份。
     *
     * 与本 fork 备份同源、文件名相同，无法靠文件名区分，因此实际判别依据是
     * 库里的表结构：含 fork 专属表（scheduled_task / token_ledger）才走本地恢复路径。
     */
    suspend fun isRikkaHubBackup(file: File): Boolean = rikkaHubImporter.looksLikeUpstreamBackup(file)

    /** 走 RikkaHub 导入路径（按列名交集搬运），返回导入统计 */
    suspend fun importRikkaHubBackup(file: File): RikkaHubImporter.ImportResult {
        val items = localBackupItems.value
        val result = rikkaHubImporter.importFromZip(
            zipFile = file,
            includeDatabase = BackupItem.DATABASE in items,
            includeSettings = BackupItem.SETTINGS in items,
            includeFiles = BackupItem.FILES in items,
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
}
