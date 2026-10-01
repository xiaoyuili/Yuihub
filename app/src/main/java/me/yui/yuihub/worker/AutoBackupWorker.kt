package me.yui.yuihub.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.sync.BackupItem
import me.yui.yuihub.data.sync.LocalBackupService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 自动备份 Worker。
 *
 * 备份内容固定为「设置 + 数据库 + 文件」，不含工作区（rootfs 体积大且可重建）。
 * 成功后删除上一份自动备份，只保留最新一份；手动备份不受影响。
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val backupService: LocalBackupService by inject()
    private val settingsStore: SettingsStore by inject()

    override suspend fun doWork(): Result {
        val config = settingsStore.settingsFlow.value.backupReminderConfig
        if (!config.autoBackupEnabled) {
            Log.i(TAG, "doWork: auto backup disabled, skip")
            return Result.success()
        }

        return try {
            val items = listOf(BackupItem.SETTINGS, BackupItem.DATABASE, BackupItem.FILES)
            val temp = backupService.prepareBackupFile(items)

            val targetDir = AutoBackupScheduler.backupDir(applicationContext)
            val fileName = AutoBackupScheduler.backupFileName(System.currentTimeMillis())
            val target = File(targetDir, fileName)
            temp.copyTo(target, overwrite = true)
            temp.delete()

            // 删上一份自动备份：只删配置里记录的那个文件，避免误删用户手动导出的备份
            val previousName = config.autoBackupLastFileName
            if (previousName.isNotBlank() && previousName != fileName) {
                val previous = File(targetDir, previousName)
                if (previous.exists() && previous.delete()) {
                    Log.i(TAG, "doWork: removed previous auto backup $previousName")
                }
            }

            settingsStore.update { settings ->
                settings.copy(
                    backupReminderConfig = settings.backupReminderConfig.copy(
                        autoBackupLastTime = System.currentTimeMillis(),
                        autoBackupLastFileName = fileName,
                        lastBackupTime = System.currentTimeMillis(),
                    )
                )
            }
            Log.i(TAG, "doWork: auto backup written $fileName (${target.length()} bytes)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: auto backup failed", e)
            Result.retry()
        } finally {
            // 无论成败都排下一次，否则失败一次就永久停了
            runCatching { AutoBackupScheduler.enqueueNext(applicationContext, settingsStore) }
                .onFailure { Log.e(TAG, "doWork: reschedule failed", it) }
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
    }
}
