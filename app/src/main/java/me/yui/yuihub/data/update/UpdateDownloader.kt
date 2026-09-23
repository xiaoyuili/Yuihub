package me.yui.yuihub.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APK 下载启动器：
 * - 优先复用上次测速最快的加速线路（存 SharedPreferences），HEAD 探活可用即直接用
 * - 不可用或无记录时对所有线路重新测速，选最快者并缓存
 * - 通过系统 DownloadManager 下载（通知栏进度，APK 落在 Downloads 目录）
 */
class UpdateDownloader(
    private val context: Context,
    private val checker: UpdateChecker,
) {
    /**
     * 测速并启动下载, 返回实际使用的下载 URL。
     * 所有线路均不可达时抛 [DownloadException]。
     */
    suspend fun enqueueDownload(update: AppUpdateInfo): String = withContext(Dispatchers.IO) {
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
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.enqueue(request) }
            .onFailure { throw DownloadException("DownloadManager enqueue failed", it) }
        url
    }

    /**
     * 选线路：优先复用上次缓存的线路（HEAD 探活通过即用），
     * 不可用或无记录时对所有线路测速取最快并缓存。
     */
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

    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val PREF_NAME = "yuihub.preferences"
        const val PREF_LAST_NODE = "update_last_proxy_node"

        fun apkFileName(versionName: String): String = "YuiHub-$versionName.apk"
    }
}
