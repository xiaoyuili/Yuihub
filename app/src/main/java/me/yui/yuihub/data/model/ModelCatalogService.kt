package me.yui.yuihub.data.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * models.dev 模型目录：拉取、缓存、查询。
 *
 * - 原始 api.json 约 5MB；解析压缩后写入 cacheDir，离线可用
 * - 拉取失败（离线/超时）静默降级到本地缓存；无缓存时 [resolve] 返回 null，
 *   调用方回退内置 ModelRegistry
 * - 同进程内只自动尝试一次刷新，避免反复联网
 */
class ModelCatalogService(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val cacheDir: File by lazy { File(context.cacheDir, "disk_cache/model_catalog") }
    private val cacheFile: File get() = File(cacheDir, "catalog.json")

    @Serializable
    private data class CachedCatalog(
        val v: Int = CACHE_VERSION,
        val ts: Long = 0,
        val e: Map<String, List<ModelCatalogEntry>> = emptyMap(),
    )

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    private val refreshMutex = Mutex()
    private val autoRefreshAttempted = AtomicBoolean(false)

    @Volatile
    private var catalog: Map<String, List<ModelCatalogEntry>> = emptyMap()

    val isLoaded: Boolean get() = catalog.isNotEmpty()

    /** 读取本地缓存（进程启动时调用；无缓存或损坏时静默返回 false） */
    suspend fun loadCache(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile
            if (!file.exists()) return@runCatching false
            val parsed = json.decodeFromString<CachedCatalog>(file.readText())
            if (parsed.v != CACHE_VERSION || parsed.e.isEmpty()) return@runCatching false
            catalog = parsed.e
            _lastSyncTime.value = parsed.ts
            _entryCount.value = parsed.e.values.sumOf { it.size }
            true
        }.getOrElse {
            Log.w(TAG, "loadCache failed: ${it.message}")
            false
        }
    }

    /**
     * 刷新目录。成功写入缓存并返回 true；失败保留现有数据返回 false。
     * [force] 为 false 且缓存未过期时直接跳过（不联网）。
     */
    suspend fun refresh(force: Boolean): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cacheAge = now - _lastSyncTime.value
            if (!force && isLoaded && cacheAge < CACHE_TTL_MILLIS) {
                return@withContext false
            }
            runCatching {
                val request = Request.Builder().url(API_URL).get().build()
                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string() ?: error("empty body")
                }
                val entries = ModelCatalogResolver.parse(json, body)
                if (entries.isEmpty()) error("no entries parsed")

                val snapshot = CachedCatalog(v = CACHE_VERSION, ts = now, e = entries)
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "${cacheFile.name}.tmp")
                tmp.writeText(json.encodeToString(snapshot))
                if (!tmp.renameTo(cacheFile)) {
                    tmp.copyTo(cacheFile, overwrite = true)
                    tmp.delete()
                }

                catalog = entries
                _lastSyncTime.value = now
                _entryCount.value = entries.values.sumOf { it.size }
                Log.i(TAG, "refresh ok: ${_entryCount.value} entries")
                true
            }.onFailure {
                Log.w(TAG, "refresh failed: ${it.message}")
            }.getOrDefault(false)
        }
    }

    /** 启动时调用一次：加载缓存 + 首次自动刷新（失败静默） */
    suspend fun ensureLoaded() {
        if (!isLoaded) loadCache()
        if (autoRefreshAttempted.compareAndSet(false, true)) {
            refresh(force = false)
        }
    }

    /** 按需强制同步（设置页按钮） */
    suspend fun syncNow(): Boolean = refresh(force = true)

    /**
     * 查询模型元数据。找不到返回 null（调用方回退内置 ModelRegistry）。
     * [providerName]/[baseUrl] 用于在多个供应商收录同一模型时选出最匹配的一个。
     */
    fun resolve(modelId: String, providerName: String?, baseUrl: String?): ModelInfo? =
        ModelCatalogResolver.resolve(catalog, modelId, providerName, baseUrl)

    private companion object {
        const val TAG = "ModelCatalogService"
        const val API_URL = "https://models.dev/api.json"
        const val CACHE_VERSION = 1
        const val CACHE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
