package me.yui.yuihub.data.sync

import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase

private const val TAG = "DbIntegrityCheck"

/**
 * 带 FTS 容错的数据库完整性校验。
 *
 * 为什么不能直接 `PRAGMA integrity_check`：应用库含 `message_fts` FTS5 虚拟表，
 * 其自定义 tokenizer（libsimple 的 `simple`）只在 Room 的 openHelperFactory 里注册；
 * 备份/恢复流程用的裸连接（SQLiteDatabase.openDatabase）没有该扩展，
 * integrity_check 扫到 FTS 表会因「no such tokenizer」直接抛错
 * （requery 包装为 "SQL logic error (code 1)"），导致正常备份被误判损坏。
 *
 * 解法：按表逐一校验（`PRAGMA integrity_check('<table>')`），跳过 FTS 虚拟表
 * （其内容可由业务表重建，损坏可接受）；其余任何表失败都视为库损坏。
 */
object DbIntegrityChecker {

    /** 判断表名是否为 FTS 虚拟表（影子表以 fts 表名为前缀，如 message_fts_data） */
    private fun isFtsRelated(name: String, ftsTables: Set<String>): Boolean =
        name in ftsTables || ftsTables.any { name.startsWith("${it}_") }

    private fun virtualTableNames(db: SQLiteDatabase): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND sql LIKE 'CREATE VIRTUAL TABLE%'")
            .use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet {
                    while (cursor.moveToNext()) {
                        cursor.getString(nameIndex)?.let(::add)
                    }
                }
            }

    /**
     * 完整性校验。返回 null 表示通过，否则返回首个错误的描述。
     *
     * [skipVirtualTables] 为 true 时跳过虚拟表（含 FTS），用于没有注册自定义
     * tokenizer 的裸连接；false 时做全库检查。
     */
    fun check(db: SQLiteDatabase, skipVirtualTables: Boolean = true): String? = runCatching {
        if (!skipVirtualTables) {
            val rows = db.query("PRAGMA integrity_check").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            return rows.firstOrNull { it != "ok" } ?: rows.firstOrNull()
        }

        val virtual = virtualTableNames(db)
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildList {
                while (cursor.moveToNext()) {
                    cursor.getString(nameIndex)?.let(::add)
                }
            }
        }.filter { !isFtsRelated(it, virtual) }

        for (table in tables) {
            // 表名来自 sqlite_master，反引号转义防注入式破坏（理论上库名可控即已损坏）
            val safe = table.replace("`", "``")
            val rows = db.query("PRAGMA integrity_check(`$safe`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            val error = rows.firstOrNull { it != "ok" }
            if (error != null) {
                Log.e(TAG, "integrity check failed for table $table: $error")
                return "$error (table: $table)"
            }
            // 全行扫描：触发每一页的解引用，能发现 integrity_check 摘要级检查
            // 漏掉的部分页级损坏（逐表校验跳过了 FTS 影子表，这是主盲区补偿）
            try {
                db.query("SELECT * FROM `$safe`").use { cursor ->
                    while (cursor.moveToNext()) {
                        // 读一列以强制解引用，防止惰性 BLOB 读取掩盖损坏
                        for (i in 0 until cursor.columnCount) {
                            cursor.getString(i)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "row scan failed for table $table", e)
                return "${e.message} (table: $table)"
            }
        }
        null
    }.getOrElse {
        Log.e(TAG, "integrity check threw", it)
        it.message ?: it.javaClass.simpleName
    }
}
