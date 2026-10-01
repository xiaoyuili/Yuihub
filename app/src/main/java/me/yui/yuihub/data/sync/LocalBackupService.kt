package me.yui.yuihub.data.sync

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.yui.yuihub.data.db.AppDatabase
import me.yui.yuihub.data.files.FileFolders
import me.yui.yuihub.data.files.SkillPaths
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.migration.SettingsJsonMigrator
import me.yui.yuihub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "LocalBackupService"

enum class BackupItem {
    DATABASE,
    FILES,
    SETTINGS,
}

/**
 * 本地备份：将选中的内容打成 zip（导出到本地文件 / 从本地文件恢复）。
 *
 * SETTINGS 对应 settings.json——供应商、MCP、技能启用、模型参数等。
 * DATABASE 对应 Room 数据库（聊天记录、记忆、token 账本、定时任务等）。
 * FILES 对应上传附件、技能、字体、工具输出、生成图片。
 */
class LocalBackupService(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun prepareBackupFile(items: List<BackupItem>): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            if (items.contains(BackupItem.SETTINGS)) {
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = "settings.json",
                    content = json.encodeToString(settingsStore.settingsFlow.value)
                )
            }

            if (items.contains(BackupItem.DATABASE)) {
                // 先 checkpoint: WAL 里的未合并页写回主库, 否则热拷出的快照缺最新数据,
                // 恢复后会丢最后一次会话的内容
                runCatching {
                    database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
                        .use { it.moveToFirst() }
                }
                val dbFile = context.getDatabasePath(DATABASE_NAME)
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, DATABASE_NAME)
                }

                // 只保留主库：checkpoint(TRUNCATE) 后 WAL 已清空，把空的 sidecar 也打进去
                // 会让恢复端多写一个零字节 WAL 文件，无意义且干扰「是否携带 WAL」的判断。
            }

            if (items.contains(BackupItem.FILES)) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    folderName = FileFolders.UPLOAD,
                    recursive = false,
                )
                addDirectoryToZip(
                    zipOut = zipOut,
                    folderName = FileFolders.SKILLS,
                    recursive = true,
                )
                addDirectoryToZip(
                    zipOut = zipOut,
                    folderName = FileFolders.FONTS,
                    recursive = false,
                )
                addDirectoryToZip(
                    zipOut = zipOut,
                    folderName = FileFolders.TOOL_OUTPUTS,
                    recursive = true,
                )
                // 生成图片：DB 里只存相对路径，缺了文件相册就是一堆打不开的条目
                addDirectoryToZip(
                    zipOut = zipOut,
                    folderName = FileFolders.IMAGES,
                    recursive = false,
                )
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    suspend fun restoreFromLocalFile(file: File, items: List<BackupItem>) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            restoreFromBackupFile(file, items)
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    private suspend fun restoreFromBackupFile(backupFile: File, items: List<BackupItem>) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

            // 数据库先解到暂存目录：直接覆盖真实库文件时，一旦中途失败（低内存被杀、
            // 用户强杀）留下的是半截库且无法回滚。先解到 ${cacheDir} 校验通过再原子替换。
            val stagedDatabase = if (items.contains(BackupItem.DATABASE)) {
                File(context.cacheDir, "restore_staged_$DATABASE_NAME")
            } else null

            // Room 连接全程持有 db 文件: 不先关库, 恢复后的文件会被旧连接的 WAL
            // 状态覆盖回去, 轻则数据回退重则库损坏。关库后旧查询会失败, 因此恢复
            // 完成后必须重启应用才能继续使用。
            val needRestart = items.contains(BackupItem.DATABASE)
            if (needRestart) {
                Log.w(TAG, "restoreFromBackupFile: closing database for file restore")
                database.close()
            }

            try {
                ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        entry?.let { zipEntry ->
                            when (zipEntry.name) {
                                "settings.json" -> {
                                    if (items.contains(BackupItem.SETTINGS)) {
                                        val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                        Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                                        try {
                                            val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                            val settings = json.decodeFromString<Settings>(migratedJson)
                                            settingsStore.update(settings)
                                            Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                            throw Exception("Failed to restore settings: ${e.message}")
                                        }
                                    }
                                }

                                // 老版本备份可能同时含 -wal/-shm：统一收到暂存目录附近，
                                // 稍后由完整性校验决定是否采用，绝不直接落进 databases/
                                DATABASE_NAME, "$DATABASE_NAME-wal", "$DATABASE_NAME-shm" -> {
                                    if (items.contains(BackupItem.DATABASE)) {
                                        val target = when (zipEntry.name) {
                                            DATABASE_NAME -> stagedDatabase
                                            "$DATABASE_NAME-wal" ->
                                                File(context.cacheDir, "restore_staged_$DATABASE_NAME-wal")

                                            else ->
                                                File(context.cacheDir, "restore_staged_$DATABASE_NAME-shm")
                                        } ?: return@let
                                        target.parentFile?.mkdirs()
                                        FileOutputStream(target).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(TAG, "restoreFromBackupFile: Staged ${zipEntry.name} (${target.length()} bytes)")
                                    }
                                }

                                else -> if (items.contains(BackupItem.FILES)) {
                                    restoreFileEntry(zipIn, zipEntry.name)
                                } else {
                                    Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                                }
                            }

                            zipIn.closeEntry()
                        }
                    }
                }

                // 暂存的库：先回放 WAL（老格式备份才带），再校验，通过后才安装。
                // 顺序很重要：只读打开带 WAL 的库读不到 WAL 里的最新页，
                // 先校验会验到一份过时快照，且可能因缺 -shm 而打不开。
                if (stagedDatabase != null) {
                    checkpointStagedWal(stagedDatabase)
                    validateStagedDatabase(stagedDatabase)
                    installStagedDatabase(stagedDatabase)
                }
            } finally {
                // 暂存残留一律清理，避免污染下次恢复
                listOf(DATABASE_NAME, "$DATABASE_NAME-wal", "$DATABASE_NAME-shm").forEach { name ->
                    File(context.cacheDir, "restore_staged_$name").delete()
                }
            }

            Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
        }

    /**
     * 回放暂存库旁的 WAL（只有老格式备份会带）。
     * 不回放就校验/安装，会拿到一份不含最新事务的快照。
     */
    private fun checkpointStagedWal(staged: File) {
        val stagedWal = File(context.cacheDir, "restore_staged_$DATABASE_NAME-wal")
        val stagedShm = File(context.cacheDir, "restore_staged_$DATABASE_NAME-shm")
        if (!stagedWal.exists() || stagedWal.length() == 0L) {
            // 无 WAL / 空 WAL：清掉可能的 -shm，避免它影响后续打开
            stagedShm.delete()
            return
        }
        SQLiteDatabase.openDatabase(
            staged.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        Log.i(TAG, "checkpointStagedWal: replayed WAL into staged database")
    }

    /**
     * 校验暂存的数据库：非空、能被 SQLite 打开、`PRAGMA integrity_check` 通过。
     *
     * 调用前 WAL 已被回放（见 [checkpointStagedWal]），因此这里校验的就是最终内容。
     */
    private fun validateStagedDatabase(staged: File) {
        if (!staged.exists() || staged.length() == 0L) {
            throw Exception("Backup database is missing or empty")
        }

        // 用只读方式打开做完整性检查：这里只验证，不修改暂存文件。
        // 与全库打开时同用 requery 实现：混用 framework / requery 两套 SQLite
        // 在 WAL 模式下可能对同一文件产生不兼容的 sidecar 状态。
        val result = runCatching {
            SQLiteDatabase.openDatabase(
                staged.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.query("PRAGMA integrity_check").use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()
                }
            }
        }.getOrElse {
            throw Exception("Backup database could not be validated: ${it.message}")
        }
        if (!result) {
            throw Exception("Backup database failed its integrity check")
        }
        Log.i(TAG, "validateStagedDatabase: integrity check passed")
    }

    /**
     * 把校验通过的暂存库原子替换到 databases/。
     *
     * 关键点：**必须先删掉旧的 -wal / -shm**。若旧库遗留的 WAL 被新库重放，
     * 会把上一份数据库的页写进新库，轻则数据错乱重则损坏。
     */
    private fun installStagedDatabase(staged: File) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        Log.i(TAG, "installStagedDatabase: installing ${staged.absolutePath} -> ${dbFile.absolutePath}")

        // 旧 sidecar 必须先清：残留 WAL 会被新库重放
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(dbFile.parentFile, DATABASE_NAME + suffix)
            if (sidecar.exists() && !sidecar.delete()) {
                throw Exception("Could not remove stale database sidecar: ${sidecar.name}")
            }
        }

        // 同目录 rename 才是原子的；跨越 cache -> databases 用 copy + rename
        val tempInPlace = File(dbFile.parentFile, "$DATABASE_NAME.restoring")
        FileInputStream(staged).use { input ->
            FileOutputStream(tempInPlace).use { output -> input.copyTo(output) }
        }
        if (!tempInPlace.renameTo(dbFile)) {
            tempInPlace.delete()
            throw Exception("Could not install the restored database")
        }
        Log.i(TAG, "installStagedDatabase: installed (${dbFile.length()} bytes)")
    }

    /**
     * 恢复 FILES 类条目：按一级目录前缀分派。
     * 所有分支都做 canonicalPath 边界检查，拒绝 zip 条目里的路径穿越。
     */
    private fun restoreFileEntry(zipIn: ZipInputStream, entryName: String) {
        when {
            entryName.startsWith("${FileFolders.UPLOAD}/") ->
                restoreFlatEntry(zipIn, entryName, FileFolders.UPLOAD)

            entryName.startsWith("${FileFolders.SKILLS}/") ->
                restoreSkillEntry(zipIn, entryName)

            entryName.startsWith("${FileFolders.FONTS}/") ->
                restoreFlatEntry(zipIn, entryName, FileFolders.FONTS)

            entryName.startsWith("${FileFolders.TOOL_OUTPUTS}/") ->
                restorePrefixedDirectoryEntry(zipIn, entryName, FileFolders.TOOL_OUTPUTS)

            entryName.startsWith("${FileFolders.IMAGES}/") ->
                restoreFlatEntry(zipIn, entryName, FileFolders.IMAGES)

            else -> Log.i(TAG, "restoreFileEntry: Skipping entry $entryName")
        }
    }

    /** 平铺目录（upload/fonts/images）：只接受一级文件名，不接受子路径 */
    private fun restoreFlatEntry(zipIn: ZipInputStream, entryName: String, folderName: String) {
        val fileName = entryName.substringAfter("$folderName/")
        if (fileName.isBlank() || fileName.contains('/')) {
            Log.w(TAG, "restoreFlatEntry: Rejected nested or empty name $entryName")
            return
        }
        val folder = File(context.filesDir, folderName).apply { mkdirs() }
        val targetFile = File(folder, fileName)
        if (!isInside(folder, targetFile)) {
            Log.w(TAG, "restoreFlatEntry: Rejected path escape $entryName")
            return
        }
        FileOutputStream(targetFile).use { outputStream -> zipIn.copyTo(outputStream) }
        Log.i(TAG, "restoreFlatEntry: Restored $entryName (${targetFile.length()} bytes)")
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    /** 按目录打包；[recursive] 决定是否含子目录（技能/工具输出需要，其余为一级平铺） */
    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        folderName: String,
        recursive: Boolean,
    ) {
        val directory = File(context.filesDir, folderName)
        if (!directory.exists() || !directory.isDirectory) {
            Log.w(TAG, "addDirectoryToZip: $folderName does not exist or is not a directory")
            return
        }
        Log.i(TAG, "addDirectoryToZip: Backing up $folderName from ${directory.absolutePath}")

        val files = if (recursive) {
            directory.walkTopDown().filter { it.isFile }.toList()
        } else {
            directory.listFiles().orEmpty().filter { it.isFile }
        }
        files.forEach { file ->
            val relativePath = file.relativeTo(directory).invariantSeparatorsPath
            addFileToZip(zipOut, file, "$folderName/$relativePath")
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreSkillEntry: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreSkillEntry: Restored $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreSkillEntry: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun restorePrefixedDirectoryEntry(
        zipIn: ZipInputStream,
        entryName: String,
        folderName: String,
    ) {
        val relativePath = entryName.substringAfter("$folderName/")
        if (relativePath.isBlank()) {
            Log.w(TAG, "restorePrefixedDirectoryEntry: Invalid entry $entryName")
            return
        }
        val root = File(context.filesDir, folderName).apply { mkdirs() }
        val targetFile = File(root, relativePath)
        // 用 canonicalPath 判定而不是字符串 contains("..")：`a/../../b` 这类能被前者拦住
        if (!isInside(root, targetFile)) {
            Log.w(TAG, "restorePrefixedDirectoryEntry: Rejected path escape $entryName")
            return
        }
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { outputStream ->
            zipIn.copyTo(outputStream)
        }
        Log.i(TAG, "restorePrefixedDirectoryEntry: Restored $entryName (${targetFile.length()} bytes)")
    }

    /** target 的规范化路径是否落在 root 目录内（root 自身不算） */
    private fun isInside(root: File, target: File): Boolean {
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        return targetPath != rootPath && targetPath.startsWith(rootPath + File.separator)
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }

    companion object {
        /** 数据库文件名（与 Room databaseBuilder 里的一致） */
        const val DATABASE_NAME = "rikka_hub"
    }
}
