package me.yui.yuihub.data.sync

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.yui.yuihub.BuildConfig
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.migration.SettingsJsonMigrator
import me.yui.yuihub.data.db.AppDatabase
import me.yui.yuihub.data.files.FileFolders
import me.yui.yuihub.data.files.SkillPaths
import me.yui.yuihub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "LocalBackupService"

/**
 * FILES 备份覆盖的 filesDir 子目录。新增用户数据目录时必须同步加到这里，
 * BackupCoverageTest 会断言两者不得漂移。
 *
 * 有意不纳入：workspaces/（rootfs 与容器临时文件，体积大且可重建）、
 * databases/、shared_prefs/、cache/、files/ 本身。
 */
val BACKED_UP_FILE_FOLDERS: List<String> = listOf(
    FileFolders.UPLOAD,
    FileFolders.SKILLS,
    FileFolders.FONTS,
    FileFolders.TOOL_OUTPUTS,
    FileFolders.IMAGES,
)

private const val SAFETY_DIR = "safety_backup"
private const val MAX_SAFETY_BACKUPS = 3
private val RECURSIVE_FOLDERS = setOf(FileFolders.SKILLS, FileFolders.TOOL_OUTPUTS)

/**
 * 本地备份：分类打包、清单、预览、校验与暂存恢复。
 * 工作区文件不参与；数据库是原子单元（对话/助手/记忆/收藏/任务/统计）。
 */
class LocalBackupService(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun prepareBackupFile(items: Collection<BackupItem>): File = withContext(Dispatchers.IO) {
        require(items.isNotEmpty()) { "Select at least one backup item" }
        val settings = settingsStore.settingsFlowRaw.first()
        val backupFile = File.createTempFile("backup_", ".zip", context.cacheDir)
        val timestamp = backupFile.nameWithoutExtension
        try {
            var databaseBytes = 0L
            var databaseHash = ""
            var databaseVersion = 0
            var counts: Map<String, Int> = emptyMap()
            val settingsText = json.encodeToString(backupSettings(settings))
            var fileCount = 0
            var fileBytes = 0L

            ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
                if (BackupItem.SETTINGS in items) {
                    addVirtualFileToZip(zipOut, "settings.json", settingsText)
                }
                if (BackupItem.DATABASE in items) {
                    val snapshot = File(context.cacheDir, "backup_snapshot_${timestamp}.db")
                    if (snapshot.exists()) snapshot.delete()
                    try {
                        database.openHelper.writableDatabase.execSQL(
                            "VACUUM main INTO ?",
                            arrayOf(snapshot.absolutePath),
                        )
                        check(snapshot.isFile && snapshot.length() > 0) { "Database snapshot is empty" }
                        databaseVersion = readDatabaseVersion(snapshot)
                        counts = readDatabaseCounts(snapshot)
                        databaseBytes = snapshot.length()
                        databaseHash = sha256(snapshot)
                        addFileToZip(zipOut, snapshot, DATABASE_NAME)
                    } finally {
                        snapshot.delete()
                    }
                }
                selectedFileFolders(items).forEach { folderName ->
                    val added = addDirectoryToZip(
                        zipOut = zipOut,
                        folderName = folderName,
                        recursive = folderName in RECURSIVE_FOLDERS,
                    )
                    fileCount += added.first
                    fileBytes += added.second
                }

                val manifest = BackupManifest(
                    appVersion = BuildConfig.VERSION_NAME,
                    databaseVersion = databaseVersion,
                    createdAt = System.currentTimeMillis(),
                    items = items.map { it.name },
                    databaseBytes = databaseBytes,
                    settingsBytes = settingsText.toByteArray(Charsets.UTF_8).size.toLong(),
                    fileCount = fileCount,
                    fileBytes = fileBytes,
                    counts = counts,
                    databaseSha256 = databaseHash,
                    settingsSha256 = if (BackupItem.SETTINGS in items) settingsText.sha256() else "",
                )
                addVirtualFileToZip(
                    zipOut,
                    BackupManifest.ENTRY_NAME,
                    json.encodeToString(manifest),
                )
            }

            Log.i(
                TAG,
                "prepareBackupFile: ${backupFile.name} (${backupFile.length().fileSizeToString()}) items=${items.map { it.name }}",
            )
            backupFile
        } catch (e: Throwable) {
            backupFile.delete()
            throw e
        }
    }

    /** 自动/安全备份：除工作区外的全部内容。 */
    suspend fun createSafetyBackup(): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, SAFETY_DIR).apply { mkdirs() }
        val target = File(dir, "yuihub_safety_${System.currentTimeMillis()}.zip")
        val partial = File(dir, "${target.name}.partial")
        val prepared = prepareBackupFile(BackupItem.ALL)
        try {
            prepared.inputStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(partial.renameTo(target)) { "Cannot publish safety backup" }
            listLocalBackups()
                .filter { it.kind == LocalBackupFile.Kind.SAFETY }
                .sortedByDescending { it.createdAt }
                .drop(MAX_SAFETY_BACKUPS)
                .forEach { it.file.delete() }
            Log.i(TAG, "createSafetyBackup: ${target.name} (${target.length().fileSizeToString()})")
            target
        } finally {
            prepared.delete()
            partial.delete()
        }
    }

    fun listLocalBackups(): List<LocalBackupFile> {
        val autoDir = File(context.filesDir, "auto_backup")
        val safetyDir = File(context.filesDir, SAFETY_DIR)
        return buildList {
            autoDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".zip") }
                .forEach { add(LocalBackupFile(it, LocalBackupFile.Kind.AUTO, it.lastModified())) }
            safetyDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".zip") }
                .forEach { add(LocalBackupFile(it, LocalBackupFile.Kind.SAFETY, it.lastModified())) }
        }.sortedByDescending { it.createdAt }
    }

    suspend fun inspectBackup(file: File): BackupInspection = withContext(Dispatchers.IO) {
        check(file.isFile && file.length() > 0) { "Backup file is missing or empty" }
        var manifest: BackupManifest? = null
        val present = linkedSetOf<BackupItem>()
        var fileCount = 0
        var fileBytes = 0L
        val itemBytes = linkedMapOf<BackupItem, Long>()
        val seen = hashSetOf<String>()
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                currentCoroutineContext().ensureActive()
                check(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                check(seen.size <= 100_000) { "Too many backup entries" }
                when {
                    entry.isDirectory -> Unit
                    entry.name == BackupManifest.ENTRY_NAME -> {
                        check(entry.size in 1..SETTINGS_LIMIT) { "Backup manifest is too large" }
                        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        manifest = runCatching { json.decodeFromString<BackupManifest>(text) }.getOrNull()
                        manifest?.let {
                            check(it.formatVersion <= BackupManifest.FORMAT_VERSION) {
                                "Backup format is newer than this app"
                            }
                        }
                    }
                    entry.name == "settings.json" -> present += BackupItem.SETTINGS
                    entry.name == DATABASE_NAME -> present += BackupItem.DATABASE
                    else -> {
                        BackupItem.forFolder(entry.name.substringBefore('/'))?.let { item ->
                            present += item
                        }
                        if (entry.name.substringBefore('/') in BACKED_UP_FILE_FOLDERS) {
                            fileCount++
                            fileBytes += entry.size.coerceAtLeast(0)
                            BackupItem.forFolder(entry.name.substringBefore('/'))?.let { item ->
                                itemBytes[item] = (itemBytes[item] ?: 0L) + entry.size.coerceAtLeast(0)
                            }
                        }
                    }
                }
            }
        }
        BackupInspection(
            file = file,
            archiveBytes = file.length(),
            manifest = manifest,
            presentItems = present,
            fileCount = fileCount,
            fileBytes = fileBytes,
            createdAt = manifest?.createdAt?.takeIf { it > 0 } ?: file.lastModified(),
            counts = manifest?.counts.orEmpty(),
            itemBytes = itemBytes,
        )
    }

    suspend fun calculateSizes(): BackupSizeSummary = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        val dbBytes = listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm"))
            .filter { it.isFile }
            .sumOf { it.length() }
        val settingsBytes = runCatching {
            json.encodeToString(backupSettings(settingsStore.settingsFlowRaw.first()))
                .toByteArray(Charsets.UTF_8).size.toLong()
        }.getOrDefault(0L)
        val files = BackupItem.FILE_ITEMS.associateWith { item ->
            val root = File(context.filesDir, item.folder!!)
            if (!root.isDirectory) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        BackupSizeSummary(databaseBytes = dbBytes, settingsBytes = settingsBytes, fileBytes = files)
    }

    suspend fun restoreFromLocalFile(file: File, items: Collection<BackupItem>) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: ${file.absolutePath} items=${items.map { it.name }}")
        check(file.exists()) { "Backup file does not exist" }
        check(file.canRead()) { "Cannot read backup file" }
        check(items.isNotEmpty()) { "Select at least one backup item" }
        if (items.any { it == BackupItem.DATABASE || it == BackupItem.SETTINGS }) {
            runCatching { createSafetyBackup() }
                .onFailure { Log.w(TAG, "safety backup failed; continuing restore", it) }
        }
        try {
            restoreFromBackupFile(file, items)
            Log.i(TAG, "restoreFromLocalFile: staged successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: failed", e)
            throw IOException("Restore failed: ${e.message}", e)
        }
    }

    private suspend fun restoreFromBackupFile(backupFile: File, items: Collection<BackupItem>) =
        withContext(Dispatchers.IO) {
            check(!PendingRestoreStore.hasPending(context.filesDir)) {
                "A restore is already pending; restart the app first"
            }
            ZipFile(backupFile).use { }
            val staging = File.createTempFile("restore_", "", context.cacheDir).also {
                check(it.delete() && it.mkdir()) { "Cannot create restore staging directory" }
            }
            val stagedDatabase = File(staging, DATABASE_NAME)
            var restoredSettings: Settings? = null
            var restoredSettingsHash = ""
            val stagedFiles = linkedMapOf<File, File>()
            val targets = hashSetOf<File>()
            val seen = hashSetOf<String>()
            val selectedFolders = selectedFileFolders(items)
            val budget = RestoreByteBudget()
            try {
                var manifest: BackupManifest? = null
                ZipInputStream(FileInputStream(backupFile)).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        check(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                        check(seen.size <= 100_000) { "Too many backup entries" }
                        val limit = if (entry.name == "settings.json") SETTINGS_LIMIT else ENTRY_LIMIT
                        val jobContext = currentCoroutineContext()
                        val input = budget.entry(zip, limit) { jobContext.ensureActive() }
                        when {
                            entry.isDirectory -> Unit
                            entry.name == BackupManifest.ENTRY_NAME -> {
                                val text = input.readBytes().toString(Charsets.UTF_8)
                                manifest = runCatching {
                                    json.decodeFromString<BackupManifest>(text)
                                }.getOrNull()
                                manifest?.let {
                                    check(it.formatVersion <= BackupManifest.FORMAT_VERSION) {
                                        "Backup format is newer than this app"
                                    }
                                }
                            }
                            entry.name == "settings.json" && BackupItem.SETTINGS in items -> {
                                val text = input.readBytes().toString(Charsets.UTF_8)
                                restoredSettingsHash = text.sha256()
                                restoredSettings = json.decodeFromString<Settings>(
                                    SettingsJsonMigrator.migrate(text)
                                )
                            }
                            entry.name in listOf(DATABASE_NAME, "$DATABASE_NAME-wal", "$DATABASE_NAME-shm") &&
                                BackupItem.DATABASE in items -> {
                                File(staging, entry.name).outputStream().use { input.copyTo(it) }
                            }
                            BackupItem.forFolder(entry.name.substringBefore('/'))?.folder in selectedFolders -> {
                                val target = fileEntryTarget(entry.name, items)
                                if (target != null) {
                                    check(targets.add(target)) { "Duplicate backup file: ${entry.name}" }
                                    val staged = File(staging, "attachment_${stagedFiles.size}")
                                    staged.outputStream().use { input.copyTo(it) }
                                    stagedFiles[staged] = target
                                }
                            }
                        }
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (input.read(buffer) != -1) Unit
                        zip.closeEntry()
                    }
                }
                if (BackupItem.SETTINGS in items) {
                    check(restoredSettings != null) { "Backup settings are missing" }
                    val expected = manifest?.settingsSha256.orEmpty()
                    if (expected.isNotBlank()) {
                        check(restoredSettingsHash == expected) { "Backup settings checksum mismatch" }
                    }
                }
                if (BackupItem.DATABASE in items) {
                    check(stagedDatabase.isFile && stagedDatabase.length() > 0) {
                        "Backup database is missing or empty"
                    }
                    checkpointStagedWal(stagedDatabase)
                    validateStagedDatabase(stagedDatabase)
                    val expected = manifest?.databaseSha256.orEmpty()
                    if (expected.isNotBlank()) {
                        check(sha256(stagedDatabase) == expected) { "Backup database checksum mismatch" }
                    }
                }
                val jobContext = currentCoroutineContext()
                PendingRestoreStore.stage(
                    filesDir = context.filesDir,
                    staged = stagedDatabase.takeIf { BackupItem.DATABASE in items },
                    settings = restoredSettings?.let { json.encodeToString(it) },
                    attachments = stagedFiles,
                ) { jobContext.ensureActive() }
            } finally {
                staging.deleteRecursively()
            }
        }

    private fun checkpointStagedWal(staged: File) {
        val stagedWal = File(staged.parentFile, "${staged.name}-wal")
        val stagedShm = File(staged.parentFile, "${staged.name}-shm")
        if (!stagedWal.exists() || stagedWal.length() == 0L) {
            stagedShm.delete()
            return
        }
        SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "Cannot checkpoint backup WAL" }
            }
        }
    }

    private fun validateStagedDatabase(staged: File) {
        if (!staged.exists() || staged.length() == 0L) {
            throw Exception("Backup database is missing or empty")
        }
        SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val version = readDatabaseVersion(db)
            check(version in 1..AppDatabase.DATABASE_VERSION) {
                "Backup database is from a newer app version (db=$version, current=${AppDatabase.DATABASE_VERSION})"
            }
            val error = DbIntegrityChecker.check(db)
            check(error == null) { "Backup database could not be validated: $error" }
        }
        Log.i(TAG, "validateStagedDatabase: passed")
    }

    private fun readDatabaseVersion(file: File): Int =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { readDatabaseVersion(it) }

    private fun readDatabaseVersion(db: SQLiteDatabase): Int =
        db.query("PRAGMA user_version").use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun readDatabaseCounts(file: File): Map<String, Int> = runCatching {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf(
                "conversations" to "ConversationEntity",
                "message_nodes" to "MessageNodeEntity",
                "memories" to "MemoryEntity",
                "favorites" to "FavoriteEntity",
                "scheduled_tasks" to "ScheduledTaskEntity",
                "token_usage" to "TokenUsageEventEntity",
            ).mapNotNull { (label, table) ->
                runCatching {
                    db.query("SELECT COUNT(*) FROM $table").use {
                        if (it.moveToFirst()) label to it.getInt(0) else null
                    }
                }.getOrNull()
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private suspend fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            zipOut.putNextEntry(ZipEntry(entryName))
            val jobContext = currentCoroutineContext()
            RestoreByteBudget(Long.MAX_VALUE)
                .entry(fis, Long.MAX_VALUE) { jobContext.ensureActive() }
                .copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    /** 按目录打包，返回 (文件数, 字节数)。 */
    private suspend fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        folderName: String,
        recursive: Boolean,
    ): Pair<Int, Long> {
        val directory = File(context.filesDir, folderName)
        if (!directory.isDirectory) return 0 to 0L
        val files = if (recursive) {
            directory.walkTopDown().filter { it.isFile }.toList()
        } else {
            directory.listFiles().orEmpty().filter { it.isFile }
        }
        var bytes = 0L
        files.forEach { file ->
            currentCoroutineContext().ensureActive()
            val relativePath = file.relativeTo(directory).invariantSeparatorsPath
            addFileToZip(zipOut, file, "$folderName/$relativePath")
            bytes += file.length()
        }
        return files.size to bytes
    }

    private fun isInside(root: File, target: File): Boolean {
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        return targetPath != rootPath && targetPath.startsWith(rootPath + File.separator)
    }

    suspend fun writeFileEntry(entryName: String, staged: File) {
        val target = fileEntryTarget(entryName, BackupItem.ALL) ?: return
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create file directory" }
        val temp = File.createTempFile("restore_", ".tmp", target.parentFile)
        try {
            val jobContext = currentCoroutineContext()
            staged.inputStream().use { input ->
                temp.outputStream().use { output ->
                    RestoreByteBudget().entry(input) { jobContext.ensureActive() }.copyTo(output)
                }
            }
            currentCoroutineContext().ensureActive()
            check(temp.renameTo(target)) { "Cannot install restored file: $entryName" }
        } finally {
            temp.delete()
        }
    }

    private fun fileEntryTarget(entryName: String, items: Collection<BackupItem>): File? {
        val folderName = entryName.substringBefore('/')
        val item = BackupItem.forFolder(folderName) ?: return null
        if (item !in items) return null
        val relative = entryName.substringAfter('/', "")
        require(relative.isNotBlank() && '\\' !in relative) { "Invalid backup path: $entryName" }
        val root = File(context.filesDir, folderName)
        val target = if (folderName == FileFolders.SKILLS) {
            val skill = SkillPaths.resolveSkillDir(root, relative.substringBefore('/'))
                ?: error("Invalid skill directory: $entryName")
            require('/' in relative) { "Invalid skill entry: $entryName" }
            SkillPaths.resolveSkillFile(skill, relative.substringAfter('/'))
                ?: error("Invalid skill path: $entryName")
        } else {
            require(folderName == FileFolders.TOOL_OUTPUTS || '/' !in relative) {
                "Invalid nested backup path: $entryName"
            }
            File(root, relative)
        }
        require(isInside(root, target)) { "Backup path escapes directory: $entryName" }
        return target.canonicalFile
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(content.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()
    }

    private fun selectedFileFolders(items: Collection<BackupItem>): List<String> =
        items.mapNotNull { it.folder }

    /** 设置 JSON 只保留可序列化字段；与旧备份格式一致。 */
    private fun backupSettings(settings: Settings): Settings = settings

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DATABASE_NAME = "rikka_hub"
    }
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal const val ENTRY_LIMIT = 2L * 1024 * 1024 * 1024
internal const val SETTINGS_LIMIT = 16L * 1024 * 1024
internal const val TOTAL_LIMIT = 8L * 1024 * 1024 * 1024

internal class RestoreByteBudget(private val totalLimit: Long = TOTAL_LIMIT) {
    private var total = 0L

    fun entry(input: InputStream, limit: Long = ENTRY_LIMIT, checkActive: () -> Unit = {}): InputStream =
        object : InputStream() {
            private var count = 0L

            override fun read(): Int {
                val buffer = ByteArray(1)
                return if (read(buffer, 0, 1) == -1) -1 else buffer[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkActive()
                val read = input.read(buffer, offset, length)
                if (read > 0) {
                    count += read
                    total += read
                    if (count > limit || total > totalLimit) throw IOException("Backup exceeds extraction limit")
                }
                return read
            }
        }
}
