package me.yui.yuihub.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.yui.yuihub.YuiHubApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!(applicationContext as YuiHubApp).awaitStartup()) return@withContext Result.retry()
        var temp: File? = null
        var incomplete: File? = null
        try {
            val config = settingsStore.settingsFlowRaw.first().backupReminderConfig
            if (!config.autoBackupEnabled) {
                Log.i(TAG, "doWork: auto backup disabled, skip")
                return@withContext Result.success()
            }
            val items = BackupItem.ALL
            val prepared = backupService.prepareBackupFile(items)
            temp = prepared

            val targetDir = AutoBackupScheduler.backupDir(applicationContext)
            val fileName = AutoBackupScheduler.backupFileName(System.currentTimeMillis())
            val target = File(targetDir, fileName)
            val partial = File(targetDir, "$fileName.partial")
            incomplete = partial
            prepared.inputStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(partial.renameTo(target)) { "Cannot publish auto backup" }
            incomplete = null

            settingsStore.update { settings ->
                settings.copy(
                    backupReminderConfig = settings.backupReminderConfig.copy(
                        autoBackupLastTime = System.currentTimeMillis(),
                        autoBackupLastFileName = fileName,
                        lastBackupTime = System.currentTimeMillis(),
                    )
                )
            }
            val previousName = config.autoBackupLastFileName
            if (previousName.matches(Regex("yuihub_auto_[0-9]+[.]zip")) && previousName != fileName) {
                val previous = File(targetDir, previousName)
                if (previous.exists() && previous.delete()) {
                    Log.i(TAG, "doWork: removed previous auto backup $previousName")
                }
            }
            Log.i(TAG, "doWork: auto backup written $fileName (${target.length()} bytes)")
            AutoBackupScheduler.enqueueNext(applicationContext, settingsStore, afterSuccess = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "doWork: auto backup failed", e)
            Result.retry()
        } finally {
            temp?.delete()
            incomplete?.delete()
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
    }
}
