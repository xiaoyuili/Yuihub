package me.yui.yuihub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.restoreSettingsBeforeStartup
import me.yui.yuihub.data.datastore.settingsDataStoreFile
import me.yui.yuihub.data.db.AppDatabase
import me.yui.yuihub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream

object PendingRestoreStore {
    private const val PENDING_DIR = "restore_pending"
    private const val STAGED_DB = "staged.db"
    private const val MARKER = "pending.marker"
    private const val ROLLBACK_DB = "rollback.db"
    private const val ROLLBACK_SETTINGS = "rollback_settings.pb"
    private const val APPLIED_MARKER = "applied.marker"
    private const val APPLIED_DIR = "restore_applied"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Attachment(val path: String, val size: Long)

    @Serializable
    private data class Manifest(
        val databaseSize: Long? = null,
        val settingsSize: Long? = null,
        val attachments: List<Attachment> = emptyList(),
    )

    fun pendingDir(filesDir: File): File = File(filesDir, PENDING_DIR)
    private fun appliedDir(filesDir: File): File = File(filesDir, APPLIED_DIR).apply { mkdirs() }
    private fun stagedDbFile(filesDir: File): File = File(pendingDir(filesDir), STAGED_DB)
    private fun markerFile(filesDir: File): File = File(pendingDir(filesDir), MARKER)

    fun hasPending(filesDir: File): Boolean = markerFile(filesDir).exists()

    fun hasApplied(filesDir: File): Boolean = File(filesDir, "$APPLIED_DIR/$APPLIED_MARKER").exists()

    @Synchronized
    fun stage(
        filesDir: File,
        staged: File? = null,
        settings: String? = null,
        attachments: Map<File, File> = emptyMap(),
        checkActive: () -> Unit = {},
    ): Boolean {
        check(!hasPending(filesDir)) { "A restore is already pending; restart the app first" }
        if (staged != null) check(staged.isFile && staged.length() > 0L) { "Staged database is missing or empty" }
        val dir = pendingDir(filesDir)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create pending restore directory" }
        val markerTemp = File(dir, "$MARKER.new")
        try {
            staged?.let { copySynced(it, stagedDbFile(filesDir), checkActive) }
            val settingsFile = File(dir, "settings.json")
            settings?.let {
                val bytes = it.toByteArray(Charsets.UTF_8)
                check(bytes.size.toLong() <= SETTINGS_LIMIT) { "Pending settings are too large" }
                FileOutputStream(settingsFile).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
            }
            val entries = attachments.entries.mapIndexed { index, (source, target) ->
                checkActive()
                val relative = target.canonicalFile.relativeTo(filesDir.canonicalFile).invariantSeparatorsPath
                resolveTarget(filesDir, relative)
                copySynced(source, File(dir, "attachment_$index"), checkActive)
                Attachment(relative, source.length())
            }
            val manifest = Manifest(staged?.length(), settings?.let { settingsFile.length() }, entries)
            val markerBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            check(markerBytes.size.toLong() <= SETTINGS_LIMIT) { "Restore manifest is too large" }
            FileOutputStream(markerTemp).use { output ->
                output.write(markerBytes)
                output.fd.sync()
            }
            checkActive()
            check(markerTemp.renameTo(markerFile(filesDir))) { "Cannot persist restore marker" }
            return true
        } finally {
            markerTemp.delete()
        }
    }

    fun clear(filesDir: File) {
        val marker = markerFile(filesDir)
        check(!marker.exists() || marker.delete()) { "Cannot clear restore marker" }
        pendingDir(filesDir).deleteRecursively()
    }

    suspend fun applyIfPending(context: Context, databaseFile: File): Boolean {
        val filesDir = context.filesDir
        if (!hasPending(filesDir)) return false
        val manifest = readManifest(filesDir)
        val settings = manifest.settingsSize?.let { size ->
            val file = File(pendingDir(filesDir), "settings.json")
            check(size in 1..SETTINGS_LIMIT && file.isFile && file.length() == size) { "Pending settings are incomplete" }
            JsonInstant.decodeFromString<Settings>(file.readText())
        }
        // 安装前保留当前库与设置的副本；应用若在确认前崩溃，下一次启动自动回滚。
        val settingsFile = settingsDataStoreFile(context)
        val rollbackDir = appliedDir(filesDir)
        val rollbackDb = File(rollbackDir, ROLLBACK_DB)
        val rollbackSettings = File(rollbackDir, ROLLBACK_SETTINGS)
        val hadDatabase = copyDatabaseSet(databaseFile, rollbackDb)
        val hadSettings = settingsFile.isFile && runCatching {
            copySynced(settingsFile, rollbackSettings)
            true
        }.getOrDefault(false)
        applyPending(filesDir, databaseFile, clearPending = false) { staged ->
            SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val version = db.query("PRAGMA user_version").use { if (it.moveToFirst()) it.getInt(0) else 0 }
                check(version in 1..AppDatabase.DATABASE_VERSION) { "Pending database is from an incompatible app version" }
                check(DbIntegrityChecker.check(db) == null) { "Pending database failed integrity check" }
            }
        }
        settings?.let { restoreSettingsBeforeStartup(context, it) }
        writeAppliedMarker(filesDir, hadDatabase, hadSettings)
        clear(filesDir)
        return true
    }

    /** App 启动成功后确认恢复，删除回滚副本；未确认时下次启动会自动回滚。 */
    fun confirmApplied(filesDir: File) {
        File(filesDir, APPLIED_DIR).deleteRecursively()
    }

    /** 上次恢复后应用未确认启动成功：还原恢复前的数据库与设置。 */
    fun rollbackIfUnconfirmed(context: Context, databaseFile: File) {
        val filesDir = context.filesDir
        val dir = appliedDir(filesDir)
        val marker = File(dir, APPLIED_MARKER)
        if (!marker.exists()) return
        val payload = runCatching { json.decodeFromString<RollbackMarker>(marker.readText()) }.getOrNull()
            ?: RollbackMarker()
        if (payload.hadDatabase) {
            val rollbackDb = File(dir, ROLLBACK_DB)
            check(rollbackDb.isFile) { "Rollback database is missing" }
            restoreDatabaseSet(rollbackDb, databaseFile)
        } else {
            deleteDatabaseSet(databaseFile)
        }
        val settingsFile = settingsDataStoreFile(context)
        if (payload.hadSettings) {
            val rollbackSettings = File(dir, ROLLBACK_SETTINGS)
            check(rollbackSettings.isFile) { "Rollback settings are missing" }
            copySynced(rollbackSettings, settingsFile)
        } else {
            settingsFile.delete()
        }
        confirmApplied(filesDir)
    }

    @Serializable
    private data class RollbackMarker(
        val hadDatabase: Boolean = false,
        val hadSettings: Boolean = false,
    )

    private fun writeAppliedMarker(filesDir: File, hadDatabase: Boolean, hadSettings: Boolean) {
        val marker = File(appliedDir(filesDir), APPLIED_MARKER)
        val bytes = json.encodeToString(RollbackMarker(hadDatabase, hadSettings)).toByteArray(Charsets.UTF_8)
        FileOutputStream(marker).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun copyDatabaseSet(source: File, target: File): Boolean {
        val exists = source.isFile
        if (!exists) return false
        copySynced(source, target)
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(source.path + suffix)
            if (sidecar.isFile) copySynced(sidecar, File(target.path + suffix))
        }
        return true
    }

    private fun restoreDatabaseSet(rollback: File, databaseFile: File) {
        val parent = databaseFile.parentFile!!
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create database directory" }
        listOf("", "-wal", "-shm").forEach { suffix ->
            File(databaseFile.path + suffix).delete()
            val source = File(rollback.path + suffix)
            if (source.isFile) copySynced(source, File(databaseFile.path + suffix))
        }
    }

    private fun deleteDatabaseSet(databaseFile: File) {
        listOf("", "-wal", "-shm").forEach { suffix -> File(databaseFile.path + suffix).delete() }
    }

    private fun readManifest(filesDir: File): Manifest {
        val marker = markerFile(filesDir)
        check(marker.length() <= SETTINGS_LIMIT) { "Restore manifest is too large" }
        val text = marker.readText().trim()
        return text.toLongOrNull()?.let { Manifest(databaseSize = it) }
            ?: json.decodeFromString<Manifest>(text)
    }

    internal fun applyPending(
        filesDir: File,
        databaseFile: File,
        clearPending: Boolean = true,
        validate: (File) -> Unit,
    ): Boolean {
        if (!hasPending(filesDir)) return false
        val manifest = readManifest(filesDir)
        check(!clearPending || manifest.settingsSize == null) { "Settings require startup installation" }
        val dir = pendingDir(filesDir)
        val staged = stagedDbFile(filesDir)
        manifest.databaseSize?.let { size ->
            check(size > 0 && staged.isFile && staged.length() == size) { "Pending database is missing or incomplete" }
            validate(staged)
        }
        manifest.settingsSize?.let { size ->
            val file = File(dir, "settings.json")
            check(size in 1..SETTINGS_LIMIT && file.isFile && file.length() == size) { "Pending settings are incomplete" }
        }
        val targets = manifest.attachments.mapIndexed { index, entry ->
            val source = File(dir, "attachment_$index")
            check(entry.size >= 0 && source.isFile && source.length() == entry.size) { "Pending attachment is incomplete" }
            source to resolveTarget(filesDir, entry.path)
        }
        targets.forEach { (source, target) -> installFile(source, target) }
        if (manifest.databaseSize != null) {
            val parent = databaseFile.parentFile!!
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create database directory" }
            val temp = File(parent, "${databaseFile.name}.restoring")
            try {
                copySynced(staged, temp)
                // A failed install keeps the marker so no database consumer can observe altered sidecars.
                listOf("-wal", "-shm").forEach { suffix ->
                    val sidecar = File(parent, databaseFile.name + suffix)
                    check(!sidecar.exists() || sidecar.delete()) { "Cannot remove stale database sidecar" }
                }
                check(temp.renameTo(databaseFile)) { "Could not install the pending database" }
            } finally {
                temp.delete()
            }
        }
        if (clearPending) clear(filesDir)
        return true
    }

    private fun resolveTarget(filesDir: File, path: String): File {
        require(!path.startsWith('/') && '\\' !in path && path.substringBefore('/') in BACKED_UP_FILE_FOLDERS) { "Invalid pending attachment path" }
        val filesRoot = filesDir.canonicalFile
        val root = File(filesRoot, path.substringBefore('/')).canonicalFile
        require(root.path.startsWith(filesRoot.path + File.separator)) { "Pending attachment root escapes files directory" }
        val target = File(filesRoot, path).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Pending attachment escapes directory" }
        return target
    }

    private fun installFile(source: File, target: File) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create attachment directory" }
        val temp = File.createTempFile("restore_", ".tmp", target.parentFile)
        try {
            copySynced(source, temp)
            check(temp.renameTo(target)) { "Cannot install pending attachment" }
        } finally {
            temp.delete()
        }
    }

    private fun copySynced(source: File, target: File, checkActive: () -> Unit = {}) {
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }

    internal fun integrityCheck(db: SupportSQLiteDatabase): Boolean = runCatching {
        db.query("PRAGMA integrity_check").use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == "ok"
        }
    }.getOrDefault(false)
}
