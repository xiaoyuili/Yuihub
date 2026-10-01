package me.yui.yuihub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.yui.yuihub.data.files.FileFolders
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.common.android.appTempFolder
import me.yui.yuihub.di.appModule
import me.yui.yuihub.di.dataSourceModule
import me.yui.yuihub.di.repositoryModule
import me.yui.yuihub.di.viewModelModule
import me.yui.yuihub.data.files.FilesManager
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.service.KeepAliveService
import me.yui.yuihub.utils.SystemPermissions
import me.yui.yuihub.utils.CrashHandler
import me.yui.yuihub.utils.DatabaseUtil
import me.yui.yuihub.utils.EmojiData
import me.yui.yuihub.data.repository.WorkspaceRepository
import me.yui.yuihub.data.repository.ScheduledTaskRepository
import me.yui.yuihub.data.sync.PendingRestoreStore
import me.yui.yuihub.worker.AutoBackupScheduler
import me.yui.yuihub.worker.ScheduledTaskScheduler
import me.rerere.workspace.WorkspaceManager
import me.yui.yuihub.utils.StartupTracer
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "YuiHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val KEEP_AWAKE_NOTIFICATION_CHANNEL_ID = "keep_awake"

/** 定时任务完成通知（悬浮提醒，类似聊天软件收到好友消息） */
const val AUTOMATION_NOTIFICATION_CHANNEL_ID = "automation_completed"

class YuiHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupTracer.begin()

        // 补完上次被中断的数据库恢复。必须在 startKoin 之前：
        // 一旦 Koin 建起 AppDatabase，它就会持有旧文件，此时再替换会引发不确定状态。
        PendingRestoreStore.applyIfPending(
            filesDir = filesDir,
            databaseFile = getDatabasePath("rikka_hub"),
        )

        startKoin {
            // Koin 解析日志全量输出会拖慢冷启动; 出问题时改回 androidLogger() 排查
            androidLogger(level = org.koin.core.logger.Level.ERROR)
            androidContext(this@YuiHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        StartupTracer.mark("Koin启动")
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        StartupTracer.mark("crashHandler")

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // extract builtin skills (first launch or version bump; deleted ones keep tombstones)
        // 已移除内置 skills, 历史版本的墓碑标记与释放逻辑一并删除

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // preload emoji data (large JSON parse) off the main thread
        preloadEmojiData()

        // 同步常驻保活服务与设置开关
        syncKeepAwakeService()

        // 重建定时任务调度（跨重启/恢复备份后校准）
        rescheduleAutomationTasks()

        // 重建自动备份调度
        rescheduleAutoBackup()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val count = get<SettingsStore>().incrementLaunchCount()
                Log.i(TAG, "incrementLaunchCount: $count")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    /**
     * 重建定时任务调度：WorkManager 会跨重启保留已入队任务，但系统可能因省电策略丢弃，
     * 且升级/恢复备份后任务表可能变化；启动时按数据库现状全量重排一次最稳。
     */
    private fun rescheduleAutomationTasks() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val repository = get<ScheduledTaskRepository>()
                ScheduledTaskScheduler.rescheduleAll(this@YuiHubApp, repository)
            }.onFailure {
                Log.e(TAG, "rescheduleAutomationTasks failed", it)
            }
        }
    }

    /** 重建自动备份调度（开关/间隔变更与重启后校准） */
    private fun rescheduleAutoBackup() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                AutoBackupScheduler.enqueueNext(this@YuiHubApp, get<SettingsStore>())
            }.onFailure {
                Log.e(TAG, "rescheduleAutoBackup failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    // 738KB emoji JSON 解析较重：提前在 IO 线程预热，避免首次打开表情面板时卡顿
    private fun preloadEmojiData() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<EmojiData>()
            }.onFailure {
                Log.e(TAG, "preloadEmojiData failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        // 定时任务完成通知：重要性拉满 + 震动，保证以悬浮横幅弹出（类似收到聊天消息）
        val automationChannel = NotificationChannelCompat
            .Builder(
                AUTOMATION_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_automation))
            .setDescription(getString(R.string.notification_channel_automation_desc))
            .setVibrationEnabled(true)
            .setShowBadge(true)
            .build()
        notificationManager.createNotificationChannel(automationChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val keepAwakeChannel = NotificationChannelCompat
            .Builder(KEEP_AWAKE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_keep_awake))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(keepAwakeChannel)
    }

    /**
     * 按 keepAwakeEnabled 拉起/停止常驻保活服务，并跟随设置变化。
     *
     * 通知权限未授予时不启动：前台服务必须有可见通知，否则 startForeground 会抛异常。
     */
    private fun syncKeepAwakeService() {
        get<AppScope>().launch {
            get<SettingsStore>().settingsFlowRaw
                .map { it.keepAwakeEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled && SystemPermissions.isNotificationEnabled(this@YuiHubApp)) {
                        KeepAliveService.start(this@YuiHubApp)
                    } else {
                        if (KeepAliveService.isRunning()) {
                            KeepAliveService.stop(this@YuiHubApp)
                        }
                    }
                }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, KeepAliveService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
