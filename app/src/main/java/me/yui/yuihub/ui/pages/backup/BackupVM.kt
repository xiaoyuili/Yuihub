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
import java.io.File

class BackupVM(
    private val settingsStore: SettingsStore,
    private val localBackup: LocalBackupService,
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
