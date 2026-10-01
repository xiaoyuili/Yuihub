package me.yui.yuihub.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.yui.yuihub.AppScope
import me.yui.yuihub.utils.SystemPermissions

/**
 * APK 下载管理器（Koin 单例）。
 *
 * 相比旧实现只做到「已启动」就返回，这里把整个下载过程暴露为可观察状态：
 * 测速 → 下载（含进度）→ 完成/失败，供更新弹窗展示，避免用户点完更新后
 * 只看到一闪而过的提示、不知道后续进展。
 *
 * 下载本体仍交给系统 DownloadManager：弹窗关闭、切页面、退到后台都继续，
 * 通知栏同步显示进度；应用内弹窗只负责把状态与进度可见化。
 */
class UpdateDownloader(
    private val context: Context,
    private val checker: UpdateChecker,
    private val appScope: AppScope,
) {
    sealed interface State {
        data object Idle : State

        /** 正在测试各加速线路（HEAD 探活），此阶段尚未产生下载 */
        data object TestingRoutes : State

        /** 正在下载；进度来自对系统 DownloadManager 的轮询 */
        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long,
            /** 0f..1f；服务端未提供 Content-Length 时为 null（进度未知） */
            val progress: Float?,
        ) : State

        /** 测速失败或下载失败 */
        data class Failed(val reason: String?) : State

        /** 下载完成，可拉起系统安装器 */
        data object Completed : State
    }

    /** 拉起安装器的结果 */
    enum class InstallOutcome {
        /** 已调起系统安装器 */
        LAUNCHED,

        /** 未授予「安装未知应用」，已跳转授权页 */
        NEED_PERMISSION,

        /** 安装包不可用（文件被删或下载记录失效） */
        UNAVAILABLE,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var activeUpdate: AppUpdateInfo? = null
    private var downloadId: Long? = null

    // 操作代际：start/cancel 都会自增；异步的 cancel 协程只在代际未变时
    // 才清理状态与下载 id，避免「取消后立刻重新开始」时把新任务的状态或
    // 下载记录误删
    private var epoch = 0

    /** 开始下载。进行中重复调用会被忽略；失败后重试用 [retry]。 */
    fun start(update: AppUpdateInfo) {
        if (job?.isActive == true) return
        val generation = ++epoch
        activeUpdate = update
        _state.value = State.TestingRoutes
        job = appScope.launch {
            try {
                // 进程被杀后重启的场景：系统 DownloadManager 里的记录可能还在。
                // - 仍在进行：接管轮询而不是重复入队（避免下载两份同样的 APK）
                // - 已完成：直接进入可安装状态
                val existing = withContext(Dispatchers.IO) { findExistingDownload(update.versionName) }
                when {
                    existing != null && existing.completed -> {
                        downloadId = existing.id
                        _state.value = State.Completed
                    }

                    existing != null -> {
                        downloadId = existing.id
                        trackProgress(existing.id)
                    }

                    else -> {
                        val id = withContext(Dispatchers.IO) { enqueue(update) }
                        downloadId = id
                        trackProgress(id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == epoch) {
                    _state.value = State.Failed(e.message)
                }
            }
        }
    }

    /** 失败后重试当前版本 */
    fun retry() {
        activeUpdate?.let(::start)
    }

    /**
     * 取消测速/下载并回到未开始状态（已入队的系统下载会被移除）。
     *
     * 用 cancelAndJoin 等协程真正结束再置 Idle：否则被取消的轮询可能
     * 在置 Idle 之后又写回一个过期的 Downloading 状态。
     */
    fun cancel() {
        val running = job
        val generation = ++epoch
        // 先捕获当前下载 id：取消后用户可能立即重新开始，那时共享字段已被新任务写入，
        // 要移除的是本次取消对应的这一条
        val idToRemove = downloadId
        job = null
        appScope.launch {
            if (running != null) {
                running.cancelAndJoin()
            }
            idToRemove?.let { id -> runCatching { downloadManager().remove(id) } }
            // 代际已变说明期间用户又点了开始，不能覆盖新任务的状态
            if (generation != epoch) return@launch
            downloadId = null
            _state.value = State.Idle
        }
    }

    /**
     * 拉起系统安装器安装已下载的 APK。
     * 需要 Activity 上下文来跳转「安装未知应用」授权页（[SystemPermissions.ensureInstallPermission]）。
     */
    fun install(activityContext: Context): InstallOutcome {
        val id = downloadId ?: return InstallOutcome.UNAVAILABLE
        if (!SystemPermissions.ensureInstallPermission(activityContext)) {
            return InstallOutcome.NEED_PERMISSION
        }
        val uri = runCatching { downloadManager().getUriForDownloadedFile(id) }.getOrNull()
            ?: return InstallOutcome.UNAVAILABLE
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            activityContext.startActivity(intent)
            InstallOutcome.LAUNCHED
        }.getOrDefault(InstallOutcome.UNAVAILABLE)
    }

    private suspend fun trackProgress(id: Long) = withContext(Dispatchers.IO) {
        val dm = downloadManager()
        while (true) {
            val snapshot = queryDownload(dm, id)
            when {
                // 下载记录被移除（用户在通知栏取消）：回到未开始，不算失败
                snapshot == null -> {
                    _state.value = State.Idle
                    return@withContext
                }

                snapshot.status == DownloadManager.STATUS_SUCCESSFUL -> {
                    _state.value = State.Completed
                    return@withContext
                }

                snapshot.status == DownloadManager.STATUS_FAILED -> {
                    _state.value = State.Failed(null)
                    return@withContext
                }

                else -> {
                    _state.value = State.Downloading(
                        downloadedBytes = snapshot.downloadedBytes,
                        totalBytes = snapshot.totalBytes,
                        progress = downloadProgress(snapshot.downloadedBytes, snapshot.totalBytes),
                    )
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private data class DownloadSnapshot(
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    )

    private fun queryDownload(dm: DownloadManager, id: Long): DownloadSnapshot? =
        dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            fun int(column: String): Int =
                cursor.getColumnIndex(column).let { if (it >= 0) cursor.getInt(it) else 0 }

            fun long(column: String): Long =
                cursor.getColumnIndex(column).let { if (it >= 0) cursor.getLong(it) else 0L }

            DownloadSnapshot(
                status = int(DownloadManager.COLUMN_STATUS),
                downloadedBytes = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                totalBytes = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
        }

    /** 测速选线 + 入队，返回 DownloadManager 的下载 id */
    private suspend fun enqueue(update: AppUpdateInfo): Long = withContext(Dispatchers.IO) {
        val route = pickRoute(update.downloadUrl)
        val url = checker.buildDownloadUrl(update.downloadUrl, route)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("YuiHub ${update.versionName}")
            setDescription(update.versionName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, apkFileName(update.versionName))
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val id = downloadManager().enqueue(request)
        // enqueue 是阻塞调用，期间发生取消不会中断它；此时若已取消，
        // 刚入队的下载会无人跟踪（用户在应用内取消却仍在后台下载），移除它
        if (!currentCoroutineContext().isActive) {
            runCatching { downloadManager().remove(id) }
            throw CancellationException("Download enqueue cancelled")
        }
        id
    }

    private data class ExistingDownload(val id: Long, val completed: Boolean)

    /**
     * 查找同一版本已有的系统下载（应用重启后接管用）。
     * 以 description（版本号）匹配，与 [enqueue] 写入的值对应。
     * 优先返回未完成的下载（继续轮询进度）；没有时再退到已完成（直接可安装）。
     */
    private fun findExistingDownload(versionName: String): ExistingDownload? {
        val dm = downloadManager()

        fun queryIds(statusFilter: Int): List<Long> =
            dm.query(DownloadManager.Query().setFilterByStatus(statusFilter)).use { cursor ->
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val descriptionIndex = cursor.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION)
                if (idIndex < 0 || descriptionIndex < 0) return emptyList()
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(descriptionIndex) == versionName) {
                            add(cursor.getLong(idIndex))
                        }
                    }
                }
            }

        queryIds(
            DownloadManager.STATUS_PENDING or
                DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PAUSED
        ).lastOrNull()?.let { return ExistingDownload(it, completed = false) }

        queryIds(DownloadManager.STATUS_SUCCESSFUL).lastOrNull()
            ?.let { return ExistingDownload(it, completed = true) }

        return null
    }

    /** 选线路：优先复用上次缓存的线路（HEAD 探活通过即用），不可用或无记录时全量测速取最快并缓存 */
    private suspend fun pickRoute(downloadUrl: String): UpdateChecker.DownloadRoute? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val cachedNode = prefs.getString(PREF_LAST_NODE, null)

        if (cachedNode != null) {
            val cached = UpdateChecker.DownloadRoute(cachedNode, 0)
            if (checker.isRouteReachable(downloadUrl, cached)) {
                return cached
            }
        }

        val routes = checker.speedTest(downloadUrl)
        if (routes.isEmpty()) throw DownloadException("All download routes unreachable")

        val best = routes.first()
        prefs.edit().putString(PREF_LAST_NODE, best.proxyNode).apply()
        return best
    }

    private fun downloadManager(): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val PREF_NAME = "yuihub.preferences"
        const val PREF_LAST_NODE = "update_last_proxy_node"
        const val POLL_INTERVAL_MS = 500L

        fun apkFileName(versionName: String): String = "YuiHub-$versionName.apk"
    }
}

/** 下载进度（0f..1f）；总大小未知（<= 0）时返回 null，表示只能用不确定进度条 */
internal fun downloadProgress(downloadedBytes: Long, totalBytes: Long): Float? =
    if (totalBytes > 0) {
        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
