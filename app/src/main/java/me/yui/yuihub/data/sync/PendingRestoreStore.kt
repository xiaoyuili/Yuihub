package me.yui.yuihub.data.sync

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "PendingRestoreStore"

/**
 * 待完成的数据库安装（恢复续传）。
 *
 * 为什么需要：恢复时的数据库安装要经历「关闭当前库 → 校验暂存库 → 搬进 databases/」，
 * 中间任何一步被系统杀进程（低内存、用户强杀、国产 ROM 清后台），
 * 用户看到的就是「恢复到一半」。把暂存库放进**持久目录**（filesDir，不是会被系统清理的 cacheDir）
 * 并留下待办标记后，下次启动可以在数据库被打开之前把这次安装补完。
 *
 * 只处理数据库这一项：设置走 DataStore 的单文件原子写、附件等文件是幂等覆盖，
 * 重做一次的成本与风险都远低于数据库被半途替换。
 */
object PendingRestoreStore {
    /** 持久暂存目录（filesDir 下，不受 cache 清理影响） */
    private const val PENDING_DIR = "restore_pending"
    private const val STAGED_DB = "staged.db"
    private const val MARKER = "pending.marker"

    fun pendingDir(filesDir: File): File = File(filesDir, PENDING_DIR)

    private fun stagedDbFile(filesDir: File): File = File(pendingDir(filesDir), STAGED_DB)

    private fun markerFile(filesDir: File): File = File(pendingDir(filesDir), MARKER)

    /** 是否有一份待完成的数据库安装 */
    fun hasPending(filesDir: File): Boolean = markerFile(filesDir).exists()

    /**
     * 把已解好的暂存库登记为「待安装」。
     *
     * 先落暂存文件、最后写标记：标记存在即代表暂存文件已就绪，
     * 不存在「标记在但文件缺失」的中间态。
     */
    fun stage(filesDir: File, staged: File): Boolean = runCatching {
        val dir = pendingDir(filesDir).apply { mkdirs() }
        if (!dir.isDirectory) error("Cannot create pending restore directory")
        stagedDbFile(filesDir).let { target ->
            if (target.exists()) target.delete()
            staged.copyTo(target, overwrite = true)
        }
        markerFile(filesDir).writeText(staged.length().toString())
        Log.i(TAG, "stage: staged database ready (${stagedDbFile(filesDir).length()} bytes)")
        true
    }.onFailure { Log.e(TAG, "stage failed", it) }
        .getOrDefault(false)

    /** 清除待办状态（安装成功或放弃时调用） */
    fun clear(filesDir: File) {
        stagedDbFile(filesDir).delete()
        markerFile(filesDir).delete()
    }

    /**
     * 若存在待完成的安装则立即完成它。
     *
     * 必须在数据库/任何 Room 消费者打开之前调用（当前在 Application.onCreate 的 startKoin 之前），
     * 否则 Room 已经持有旧文件，替换会引发难以预期的状态。
     *
     * @return 是否执行了安装
     */
    fun applyIfPending(filesDir: File, databaseFile: File): Boolean {
        if (!hasPending(filesDir)) return false
        val staged = stagedDbFile(filesDir)
        return runCatching {
            if (!staged.exists() || staged.length() == 0L) {
                Log.w(TAG, "applyIfPending: staged database missing, discarding pending restore")
                clear(filesDir)
                return@runCatching false
            }

            // 与恢复流程同款校验：不接受损坏的库
            if (!integrityOk(staged)) {
                Log.e(TAG, "applyIfPending: staged database failed integrity check, discarding")
                clear(filesDir)
                return@runCatching false
            }

            // 清理可能残留的临时文件（上次被杀留下的）
            val temp = File(databaseFile.parentFile, "${databaseFile.name}.restoring")
            if (temp.exists()) temp.delete()

            // 旧 sidecar 必须先清：残留 WAL 会被新库重放
            listOf("-wal", "-shm").forEach { suffix ->
                val sidecar = File(databaseFile.parentFile, databaseFile.name + suffix)
                if (sidecar.exists()) sidecar.delete()
            }

            databaseFile.parentFile?.mkdirs()
            FileInputStream(staged).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(databaseFile)) {
                temp.delete()
                error("Could not install the pending database")
            }

            clear(filesDir)
            Log.i(TAG, "applyIfPending: pending database installed (${databaseFile.length()} bytes)")
            true
        }.onFailure { Log.e(TAG, "applyIfPending failed", it) }
            .getOrDefault(false)
    }

    private fun integrityOk(dbFile: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.query("PRAGMA integrity_check").use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok"
            }
        }
    }.onFailure { Log.e(TAG, "integrityOk failed", it) }
        .getOrDefault(false)

    /** 供 LocalBackupService 复用同一套校验实现 */
    internal fun integrityCheck(db: SupportSQLiteDatabase): Boolean = runCatching {
        db.query("PRAGMA integrity_check").use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == "ok"
        }
    }.getOrDefault(false)
}
