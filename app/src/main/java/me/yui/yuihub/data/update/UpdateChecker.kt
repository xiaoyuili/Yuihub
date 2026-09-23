package me.yui.yuihub.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.yui.yuihub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateInfo(
    val versionName: String,
    // 仅用于展示；GitHub Release 一般不含内部版本号，可能为 null
    val versionCode: Long?,
    val releaseNotes: String,
    val downloadUrl: String,
    val htmlUrl: String,
)

class UpdateChecker(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    sealed interface CheckResult {
        data class UpdateAvailable(val update: AppUpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data object Error : CheckResult
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_URL?per_page=1")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CheckResult.Error
                response.body?.string() ?: return@withContext CheckResult.Error
            }
        }.getOrElse { return@withContext CheckResult.Error }

        val release = runCatching {
            json.decodeFromString<List<GitHubRelease>>(body).firstOrNull { !it.draft }
        }.getOrNull() ?: return@withContext CheckResult.Error

        val asset = release.assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .minByOrNull { it.size } ?: return@withContext CheckResult.Error

        // 版本号取自 APK 附件文件名(如 YuiHub-v2.5.3-arm64-v8a.apk → 2.5.3)，与本地版本名逐段比较
        val remoteVersion = parseVersion(asset.name) ?: return@withContext CheckResult.Error
        if (!isNewer(remoteVersion, BuildConfig.VERSION_NAME)) return@withContext CheckResult.UpToDate

        CheckResult.UpdateAvailable(
            AppUpdateInfo(
                versionName = remoteVersion,
                versionCode = release.tagCode,
                releaseNotes = release.body.orEmpty(),
                downloadUrl = asset.browser_download_url,
                htmlUrl = release.html_url,
            )
        )
    }

    /**
     * 对所有下载线路测速, 返回按响应耗时升序排列的线路列表。
     * 线路 URL 格式: <加速节点域名>/<原始URL> (github.akams.cn 系加速站的通用格式),
     * GitHub 直连为原始 URL 本身。
     */
    suspend fun speedTest(downloadUrl: String): List<DownloadRoute> = withContext(Dispatchers.IO) {
        coroutineScope {
            val candidates = buildList {
                add(null) // GitHub 直连
                PROXY_NODES.forEach { node ->
                    add(node)
                }
            }
            candidates.map { node ->
                async {
                    val url = if (node == null) downloadUrl else "https://$node/$downloadUrl"
                    val elapsed = measureHead(url)
                    DownloadRoute(proxyNode = node, elapsedMs = elapsed)
                }
            }.awaitAll()
        }.filter { it.elapsedMs < TIMEOUT_FALLBACK_MS }.sortedBy { it.elapsedMs }
    }

    /**
     * 按线路生成实际下载 URL
     */
    fun buildDownloadUrl(downloadUrl: String, route: DownloadRoute?): String {
        val node = route?.proxyNode ?: return downloadUrl
        return "https://$node/$downloadUrl"
    }

    /**
     * 探活单条线路（HEAD 请求可达即视为可用）
     */
    suspend fun isRouteReachable(downloadUrl: String, route: DownloadRoute): Boolean =
        withContext(Dispatchers.IO) {
            measureHead(buildDownloadUrl(downloadUrl, route)) != Long.MAX_VALUE
        }

    private fun measureHead(url: String): Long {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            val start = System.currentTimeMillis()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Long.MAX_VALUE
                System.currentTimeMillis() - start
            }
        }.getOrDefault(Long.MAX_VALUE)
    }

    @Serializable
    private data class GitHubAsset(
        val name: String = "",
        val browser_download_url: String = "",
        val size: Long = 0,
    )

    @Serializable
    private data class GitHubRelease(
        val tag_name: String = "",
        val body: String? = null,
        val html_url: String = "",
        val draft: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    ) {
        // tag 形如 "v2.5.3+228" 时取 "+" 后的数字用于展示；无则 null
        val tagCode: Long? by lazy {
            tag_name.substringAfterLast('+', "").toLongOrNull()
        }
    }

    data class DownloadRoute(
        val proxyNode: String?, // null = GitHub 直连
        val elapsedMs: Long,
    )

    private companion object {
        const val API_URL = "https://api.github.com/repos/xiaoyuili/Yuihub/releases"
        const val TIMEOUT_FALLBACK_MS = 9_999L

        // github.akams.cn 内置的公共加速节点（2026-09 快照），直连不可用时按测速选用
        val PROXY_NODES = listOf(
            "gh-proxy.com",
            "gh.07150721.xyz",
            "gh.927223.xyz",
            "gh.acmsz.top",
            "gh.b52m.cn",
            "gh.bugdey.us.kg",
            "gh.catmak.name",
            "gh.chjina.com",
            "gh.ddlc.top",
            "gh.dpik.top",
            "gh.felicity.ac.cn",
            "gh.idayer.com",
            "gh.ikgy.top",
            "gh.inkchills.cn",
            "gh.jasonzeng.dev",
            "gh.jjj.gv.uy",
            "gh.meali.top",
            "gh.monlor.com",
            "gh.my-website.ccwu.cc",
            "gh.noki.icu",
            "gh.qfmc0721.cc.cd",
            "gh.ruan.dpdns.org",
            "gh.sixyin.com",
            "gh.tryxd.cn",
            "gh.zhai.edu.pl",
        )

        val VERSION_REGEX = Regex("""(\d+\.\d+(?:\.\d+)?)""")

        fun parseVersion(assetName: String): String? =
            VERSION_REGEX.find(assetName)?.groupValues?.get(1)

        // 逐段数字比较，保证 2.5.10 > 2.5.9
        fun isNewer(remote: String, local: String): Boolean {
            val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
            val b = local.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
