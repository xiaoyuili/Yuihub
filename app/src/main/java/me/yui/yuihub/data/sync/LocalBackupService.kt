package me.yui.yuihub.data.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * SETTINGS 对应 settings.json——供应商、MCP、技能启用、自进化开关、模型参数等。
 * DATABASE 对应 Room 数据库（聊天记录、记忆、自进化方法、token 账本等）。
 * FILES 对应上传附件、技能、字体、工具输出。
 */
class LocalBackupService(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
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
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, "rikka_hub.db")
                }

                val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                if (walFile.exists()) {
                    addFileToZip(zipOut, walFile, "rikka_hub-wal")
                }

                val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                if (shmFile.exists()) {
                    addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                }
            }

            if (items.contains(BackupItem.FILES)) {
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                }

                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = "${FileFolders.SKILLS}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                }

                val fontsFolder = File(context.filesDir, FileFolders.FONTS)
                if (fontsFolder.exists() && fontsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up fonts from ${fontsFolder.absolutePath}")
                    fontsFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.FONTS}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Fonts folder does not exist or is not a directory")
                }

                val toolOutputsFolder = File(context.filesDir, FileFolders.TOOL_OUTPUTS)
                if (toolOutputsFolder.exists() && toolOutputsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up tool outputs from ${toolOutputsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = toolOutputsFolder,
                        currentDir = toolOutputsFolder,
                        entryPrefix = "${FileFolders.TOOL_OUTPUTS}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Tool outputs folder does not exist or is not a directory")
                }
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

            ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
                        Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

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

                            "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                                if (items.contains(BackupItem.DATABASE)) {
                                    val dbFile = when (zipEntry.name) {
                                        "rikka_hub.db" -> context.getDatabasePath("rikka_hub")
                                        "rikka_hub-wal" -> File(
                                            context.getDatabasePath("rikka_hub").parentFile,
                                            "rikka_hub-wal"
                                        )

                                        "rikka_hub-shm" -> File(
                                            context.getDatabasePath("rikka_hub").parentFile,
                                            "rikka_hub-shm"
                                        )

                                        else -> null
                                    }

                                    dbFile?.let { targetFile ->
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restoring ${zipEntry.name} to ${targetFile.absolutePath}"
                                        )
                                        targetFile.parentFile?.mkdirs()
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                        )
                                    }
                                }
                            }

                            else -> {
                                if (items.contains(BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                                ) {
                                    val fileName = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                    if (fileName.isNotEmpty()) {
                                        val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                                        if (!uploadFolder.exists()) {
                                            uploadFolder.mkdirs()
                                            Log.i(TAG, "restoreFromBackupFile: Created upload directory")
                                        }

                                        val targetFile = File(uploadFolder, fileName)
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restoring file ${zipEntry.name} to ${targetFile.absolutePath}"
                                        )

                                        try {
                                            FileOutputStream(targetFile).use { outputStream ->
                                                zipIn.copyTo(outputStream)
                                            }
                                            Log.i(
                                                TAG,
                                                "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                            )
                                        } catch (e: Exception) {
                                            Log.e(TAG, "restoreFromBackupFile: Failed to restore file ${zipEntry.name}", e)
                                            throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                        }
                                    }
                                } else if (items.contains(BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                                ) {
                                    restoreSkillEntry(zipIn, zipEntry.name)
                                } else if (items.contains(BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.FONTS}/")
                                ) {
                                    val fileName = zipEntry.name.substringAfter("${FileFolders.FONTS}/")
                                    if (fileName.isNotEmpty() && !fileName.contains('/')) {
                                        val fontsFolder = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
                                        val targetFile = File(fontsFolder, fileName)
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                        )
                                    }
                                } else if (items.contains(BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.TOOL_OUTPUTS}/")
                                ) {
                                    restorePrefixedDirectoryEntry(
                                        zipIn = zipIn,
                                        entryName = zipEntry.name,
                                        folderName = FileFolders.TOOL_OUTPUTS,
                                    )
                                } else {
                                    Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                                }
                            }
                        }

                        zipIn.closeEntry()
                    }
                }
            }

            Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
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

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
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
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun restorePrefixedDirectoryEntry(
        zipIn: ZipInputStream,
        entryName: String,
        folderName: String,
    ) {
        val relativePath = entryName.substringAfter("$folderName/")
        if (relativePath.isBlank() || relativePath.contains("..")) {
            Log.w(TAG, "restoreFromBackupFile: Invalid entry $entryName")
            return
        }
        val root = File(context.filesDir, folderName).apply { mkdirs() }
        val targetFile = File(root, relativePath)
        if (!targetFile.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
            targetFile.canonicalPath != root.canonicalPath
        ) {
            Log.w(TAG, "restoreFromBackupFile: Rejected path escape $entryName")
            return
        }
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { outputStream ->
            zipIn.copyTo(outputStream)
        }
        Log.i(TAG, "restoreFromBackupFile: Restored $entryName (${targetFile.length()} bytes)")
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}
