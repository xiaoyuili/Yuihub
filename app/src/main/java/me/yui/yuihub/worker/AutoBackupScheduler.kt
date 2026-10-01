package me.yui.yuihub.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import me.yui.yuihub.data.datastore.SettingsStore
import java.io.File

/**
 * 自动备份调度。
 *
 * 与定时任务同样的思路：OneTime + 自行重排（Periodic 无法支持「间隔天数」这种粗粒度，
 * 且下限固定 15 分钟）。每次调度在 [enqueueNext] 时按配置算出下一次触发时刻。
 *
 * 备份文件写到 filesDir/auto_backup/（不占 cache，避免被系统清理）；
 * 每次成功后删除上一份自动备份，只保留最新一份（手动备份不受影响）。
 */
object AutoBackupScheduler {
    private const val TAG = "AutoBackupScheduler"

    private const val WORK_NAME = "auto_backup"

    /** 自动备份文件的存放目录名（在 filesDir 下） */
    const val BACKUP_DIR = "auto_backup"

    /** 重新安排下一次自动备份（开关/间隔变更、App 启动、每次跑完后调用） */
    suspend fun enqueueNext(context: Context, settingsStore: SettingsStore) {
        val config = settingsStore.settingsFlow.value.backupReminderConfig
        if (!config.autoBackupEnabled) {
            cancel(context)
            return
        }

        val intervalMs = config.autoBackupIntervalDays.coerceAtLeast(1) * DAY_MS
        val last = config.autoBackupLastTime
        val now = System.currentTimeMillis()
        // 首次启用：从现在起算一个完整间隔，避免立刻触发一次
        val next = if (last <= 0) now + intervalMs else (last + intervalMs).coerceAtLeast(now + MIN_DELAY_MS)

        runCatching {
            val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setInitialDelay((next - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            Log.i(TAG, "enqueueNext: scheduled in ${(next - now) / 60_000} min")
        }.onFailure {
            Log.e(TAG, "enqueueNext failed", it)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }.onFailure { Log.e(TAG, "cancel failed", it) }
    }

    /** 自动备份目录（不存在时创建） */
    fun backupDir(context: Context): File =
        File(context.filesDir, BACKUP_DIR).apply { mkdirs() }

    /** 目录下现有的自动备份文件（按修改时间倒序，最新在前） */
    fun listBackups(context: Context): List<File> =
        backupDir(context).listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".zip") }
            .sortedByDescending { it.lastModified() }

    /** 备份文件名：yuihub_auto_<时间戳>.zip */
    fun backupFileName(timestamp: Long): String = "yuihub_auto_$timestamp.zip"

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_DELAY_MS = 60_000L
}
