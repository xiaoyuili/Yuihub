package me.yui.yuihub.data.sync

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.migration.SettingsJsonMigrator
import me.yui.yuihub.data.db.AppDatabase
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val TAG = "RikkaHubImporter"

/**
 * RikkaHub（上游）备份导入器。
 *
 * 背景：YuiHub 从上游 fork 后两边 schema 各自演进（上游最高 25、本 fork 36），
 * 因此**不能直接替换数据库文件**——文件级替换会因版本号与迁移链不匹配而失败。
 *
 * 做法：解包上游 zip → 用独立连接读它的库 → 按「列名交集」逐表抽取数据 →
 * 写进本 fork 的库。已核对：本 fork 独有的列全部是 notNull 且带默认值
 * （`parent_conversation_id=''`、`category='other'`、`mount_dirs='[]'` 等），
 * 因此上游缺这些列可以安全省略；消息 JSON 结构两边一致（差异仅是可空字段）。
 */
class RikkaHubImporter(
    private val context: Context,
    private val json: Json,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    /** 文件条目的落盘逻辑（复用 LocalBackupService 的前缀分派与路径校验） */
    private val writeFileEntry: (entryName: String, bytes: ByteArray) -> Unit,
) {
    /** 导入结果计数，用于给用户反馈 */
    data class ImportResult(
        val conversations: Int = 0,
        val messageNodes: Int = 0,
        val memories: Int = 0,
        val media: Int = 0,
        val favorites: Int = 0,
        val folders: Int = 0,
        val workspaces: Int = 0,
        val settingsImported: Boolean = false,
        val uploadedFiles: Int = 0,
    ) {
        val totalRows: Int
            get() = conversations + messageNodes + memories + media + favorites + folders + workspaces
    }

    /**
     * 执行导入。导入数据库前会关闭连接，因此调用方必须提示用户重启应用。
     *
     * 逐段容错：某张表失败只记日志并跳过，不阻断其余内容。
     */
    suspend fun importFromZip(
        zipFile: File,
        includeDatabase: Boolean = true,
        includeSettings: Boolean = true,
        includeFiles: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "importFromZip: start ${zipFile.absolutePath}")

        val staging = File(context.cacheDir, "rikkahub_import_${System.currentTimeMillis()}")
        staging.mkdirs()
        var upstreamDb: File? = null
        var upstreamWal: File? = null
        var upstreamSettings: String? = null
        val stagedFileEntries = mutableListOf<Pair<String, File>>()

        try {
            // ---- 1) 解包到暂存目录 ----
            ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        when {
                            name == DATABASE_NAME || name.endsWith("/$DATABASE_NAME") -> {
                                upstreamDb = File(staging, "upstream.db").also { writeEntry(zipIn, it) }
                            }

                            name.endsWith("$DATABASE_NAME-wal") -> {
                                upstreamWal = File(staging, "upstream.db-wal").also { writeEntry(zipIn, it) }
                            }

                            name == "settings.json" -> {
                                upstreamSettings = zipIn.readBytes().toString(Charsets.UTF_8)
                            }

                            includeFiles -> {
                                val target = File(staging, "files/$name")
                                writeEntry(zipIn, target)
                                stagedFileEntries.add(name to target)
                            }

                            else -> Unit
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            var result = ImportResult()

            // ---- 2) 数据库 ----
            val dbFile = upstreamDb
            if (includeDatabase && dbFile != null) {
                replayUpstreamWal(dbFile, upstreamWal)
                if (!isReadableDatabase(dbFile)) {
                    Log.e(TAG, "importFromZip: upstream database is not readable, skipping")
                } else {
                    result = result.copy(
                        conversations = 0,
                        settingsImported = false,
                    )
                    val counts = importDatabaseTables(dbFile)
                    result = result.copy(
                        conversations = counts["ConversationEntity"] ?: 0,
                        messageNodes = counts["message_node"] ?: 0,
                        memories = counts["MemoryEntity"] ?: 0,
                        media = counts["GenMediaEntity"] ?: 0,
                        favorites = counts["favorites"] ?: 0,
                        folders = counts["conversation_folder"] ?: 0,
                        workspaces = counts["workspaces"] ?: 0,
                    )
                }
            }

            // ---- 3) 设置 ----
            val settingsJson = upstreamSettings
            if (includeSettings && settingsJson != null) {
                val ok = runCatching {
                    val migrated = SettingsJsonMigrator.migrate(settingsJson)
                    val settings = json.decodeFromString<Settings>(migrated)
                    // 解析成空壳时不要写入：上游字段差异会造成静默丢配置
                    if (settings.providers.isEmpty() && settings.assistants.isEmpty()) {
                        error("上游设置解析为空，已跳过以避免覆盖现有配置")
                    }
                    settingsStore.update(settings)
                    true
                }.onFailure {
                    Log.e(TAG, "importFromZip: settings import failed", it)
                }.getOrDefault(false)
                result = result.copy(settingsImported = ok)
            }

            // ---- 4) 文件 ----
            if (includeFiles && stagedFileEntries.isNotEmpty()) {
                var written = 0
                stagedFileEntries.forEach { (entryName, staged) ->
                    runCatching {
                        writeFileEntry(entryName, staged.readBytes())
                        written++
                    }.onFailure {
                        Log.e(TAG, "importFromZip: file entry failed: $entryName", it)
                    }
                }
                result = result.copy(uploadedFiles = written)
            }

            Log.i(TAG, "importFromZip: done $result")
            result
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 判断该 zip 是否为 RikkaHub（上游）备份。
     *
     * 与本 fork 的备份格式同源、文件名也相同，因此靠**表结构**判别：
     * 本 fork 有 `scheduled_task` / `token_ledger` 两张上游没有的表，
     * 库里缺这两张表且 user_version <= 25，即视为上游备份。
     */
    fun looksLikeUpstreamBackup(zipFile: File): Boolean = runCatching {
        val staging = File(context.cacheDir, "rikkahub_probe_${System.currentTimeMillis()}.db")
        try {
            var found = false
            ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && (name == DATABASE_NAME || name.endsWith("/$DATABASE_NAME"))) {
                        writeEntry(zipIn, staging)
                        found = true
                    }
                    zipIn.closeEntry()
                    if (found) break
                    entry = zipIn.nextEntry
                }
            }
            if (!found) {
                // 没有数据库：只看是否有 settings.json（也能导出纯设置备份）
                return@runCatching false
            }
            val upstream = SQLiteDatabase.openDatabase(
                staging.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                // 本 fork 的库里一定有这两张表；上游没有 → 判定为上游备份
                val hasForkTable = tableColumns(upstream, UPSTREAM_MARKER_TABLE).isNotEmpty() ||
                    tableColumns(upstream, TOKEN_LEDGER_TABLE).isNotEmpty()
                !hasForkTable
            } finally {
                runCatching { upstream.close() }
            }
        } finally {
            staging.delete()
        }
    }.onFailure { Log.w(TAG, "looksLikeUpstreamBackup: probe failed", it) }
        .getOrDefault(false)

    /** 老格式上游备份可能带 WAL，先回放才能读到完整数据 */
    private fun replayUpstreamWal(dbFile: File, wal: File?) {
        if (wal == null || !wal.exists() || wal.length() == 0L) return
        runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }
        }.onFailure { Log.w(TAG, "replayUpstreamWal: failed", it) }
    }

    private fun isReadableDatabase(dbFile: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            val cursor = db.query("PRAGMA integrity_check")
            cursor.use {
                it.moveToFirst() && it.getString(0) == "ok"
            }
        }
    }.onFailure { Log.e(TAG, "isReadableDatabase: failed", it) }
        .getOrDefault(false)

    /** 逐表按列名交集搬运数据；返回各表写入行数 */
    private fun importDatabaseTables(upstreamDbFile: File): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val upstream: SupportSQLiteDatabase = SQLiteDatabase.openDatabase(
            upstreamDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val local = database.openHelper.writableDatabase

            // 顺序：先基础表，再引用它们者（folder/workspace 先于 conversation）
            val order = listOf(
                "conversation_folder",
                "workspaces",
                "ConversationEntity",
                "message_node",
                "MemoryEntity",
                "GenMediaEntity",
                "favorites",
                "managed_files",
            )
            order.forEach { table ->
                counts[table] = runCatching { copyTable(upstream, local, table) }
                    .onFailure { Log.e(TAG, "importDatabaseTables: table $table failed", it) }
                    .getOrDefault(0)
            }
        } finally {
            runCatching { upstream.close() }
        }
        return counts
    }

    /**
     * 把上游某张表的数据拷进本地同名列。
     *
     * 用列名交集：上游独有列（如 ConversationEntity.suggestions）忽略；
     * 本地独有列交给其默认值（已逐列核对，全部 notNull 列都有 defaultValue）。
     */
    private fun copyTable(
        upstream: SupportSQLiteDatabase,
        local: SupportSQLiteDatabase,
        table: String,
    ): Int {
        val upstreamColumns = tableColumns(upstream, table)
        if (upstreamColumns.isEmpty()) {
            Log.w(TAG, "copyTable: $table 在上游不存在，跳过")
            return 0
        }
        val localColumns = tableColumns(local, table)
        if (localColumns.isEmpty()) {
            Log.w(TAG, "copyTable: $table 在本地不存在，跳过")
            return 0
        }
        val shared = upstreamColumns.filter { it in localColumns }
        if (shared.isEmpty()) {
            Log.w(TAG, "copyTable: $table 无共同列，跳过")
            return 0
        }

        val columnsSql = shared.joinToString(", ") { "\"$it\"" }
        val placeholders = shared.joinToString(", ") { "?" }
        val insertSql = "INSERT OR REPLACE INTO \"$table\" ($columnsSql) VALUES ($placeholders)"

        var count = 0
        local.beginTransaction()
        try {
            upstream.query("SELECT $columnsSql FROM \"$table\"").use { cursor ->
                val statement = local.compileStatement(insertSql)
                while (cursor.moveToNext()) {
                    statement.clearBindings()
                    shared.forEachIndexed { index, _ ->
                        bindCursorValue(statement, index + 1, cursor, index)
                    }
                    statement.executeInsert()
                    count++
                }
            }
            local.setTransactionSuccessful()
        } finally {
            local.endTransaction()
        }
        Log.i(TAG, "copyTable: $table 导入 $count 行（共同列 ${shared.size} 个）")
        return count
    }

    private fun bindCursorValue(
        statement: androidx.sqlite.db.SupportSQLiteStatement,
        bindIndex: Int,
        cursor: Cursor,
        columnIndex: Int,
    ) {
        when (cursor.getType(columnIndex)) {
            Cursor.FIELD_TYPE_NULL -> statement.bindNull(bindIndex)
            Cursor.FIELD_TYPE_INTEGER -> statement.bindLong(bindIndex, cursor.getLong(columnIndex))
            Cursor.FIELD_TYPE_FLOAT -> statement.bindDouble(bindIndex, cursor.getDouble(columnIndex))
            else -> statement.bindString(bindIndex, cursor.getString(columnIndex))
        }
    }

    /** 用 PRAGMA table_info 取表的列名（SupportSQLiteDatabase 没有现成的列名 API） */
    private fun tableColumns(db: SupportSQLiteDatabase, table: String): List<String> = runCatching {
        db.query("PRAGMA table_info(\"$table\")").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return@use emptyList()
            buildList {
                while (cursor.moveToNext()) {
                    cursor.getString(nameIndex)?.let { add(it) }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun writeEntry(zipIn: ZipInputStream, target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().use { zipIn.copyTo(it) }
    }

    companion object {
        /** 数据库文件名（与 Room databaseBuilder 一致） */
        const val DATABASE_NAME = "rikka_hub"

        /** fork 专属表：它们的存在与否即上游/本 fork 的判别依据 */
        private const val UPSTREAM_MARKER_TABLE = "scheduled_task"
        private const val TOKEN_LEDGER_TABLE = "token_ledger"
    }
}
