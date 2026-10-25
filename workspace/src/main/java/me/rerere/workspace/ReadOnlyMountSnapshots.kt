package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * PRoot has no read-only binds. Executable mounts use private copies, never the original source.
 * Published generations are only inspected, never refreshed or deleted: a shell may replace any
 * child with a symlink, and a terminal or background process may still be using that generation.
 * This protects originals through these mounts, not against arbitrary host-path access via PRoot.
 */
internal class ReadOnlyMountSnapshots(
    private val maxStorageBytes: Long = 1024L * 1024 * 1024,
    private val maxGenerations: Int = 64,
) {
    private data class Fingerprint(val revision: String, val bytes: Long)
    private data class Snapshot(val fingerprint: Fingerprint, val source: File)

    private val current = ConcurrentHashMap<String, Snapshot>()

    fun prepare(
        mounts: List<WorkspaceBindMount>,
        directory: File,
        reuseUnchanged: Boolean = true,
    ): List<WorkspaceBindMount> = synchronized(storageLocks.getOrPut(directory.absolutePath) { Any() }) {
        mounts.filter { !it.readOnly || Files.exists(it.source.toPath(), NOFOLLOW_LINKS) }.map { mount ->
            if (!mount.readOnly) return@map mount
            val source = mount.source.toPath().toRealPath()
            require(Files.isDirectory(source, NOFOLLOW_LINKS)) {
                "Read-only mount source is not a directory: ${mount.target}"
            }
            val directoryPath = directory.canonicalFile.toPath()
            require(!directoryPath.startsWith(source) && !source.startsWith(directoryPath)) {
                "Read-only mount source overlaps snapshot storage: ${mount.target}"
            }
            require(!Files.isSymbolicLink(directory.toPath())) { "Snapshot storage must not be a symlink" }
            Files.createDirectories(directoryPath)
            val fingerprint = inspect(source)
            val key = "${directory.absolutePath}\u0000${mount.source.absolutePath}\u0000${mount.target}"
            val previous = current[key]
            val snapshot = if (reuseUnchanged && previous?.fingerprint == fingerprint &&
                !Files.isSymbolicLink(previous.source.toPath()) &&
                previous.source.canonicalFile == previous.source.absoluteFile &&
                inspectPublished(previous.source.toPath()) == fingerprint
            ) {
                previous.source
            } else {
                requireCapacity(directoryPath, fingerprint.bytes)
                val generation = Files.createTempDirectory(directoryPath, "generation-")
                try {
                    val copied = inspect(source, generation)
                    require(copied == fingerprint) { "Read-only mount changed while creating snapshot: ${mount.target}; retry" }
                    generation.toFile().also {
                        if (reuseUnchanged) current[key] = Snapshot(copied, it)
                    }
                } catch (error: Throwable) {
                    discard(generation)
                    throw error
                }
            }
            mount.copy(source = snapshot, readOnly = false)
        }
    }

    private fun inspectPublished(source: Path): Fingerprint? = try {
        inspect(source, allowSymlinks = false)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun inspect(source: Path, destination: Path? = null, allowSymlinks: Boolean = true): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        visit(source, source, destination, "", mutableSetOf(), digest, allowSymlinks) { count ->
            bytes += count
            require(bytes <= maxStorageBytes) { "Read-only snapshot exceeds storage budget ($maxStorageBytes bytes)" }
        }
        return Fingerprint(digest.digest().toHex(), bytes)
    }

    private fun visit(
        sourceRoot: Path,
        source: Path,
        destination: Path?,
        relative: String,
        ancestors: MutableSet<Path>,
        digest: MessageDigest,
        allowSymlinks: Boolean,
        accountBytes: (Int) -> Unit,
    ) {
        require(allowSymlinks || !Files.isSymbolicLink(source)) { "Published snapshot contains a symlink" }
        val resolved = source.toRealPath()
        require(resolved.startsWith(sourceRoot)) { "Read-only mount symlink escapes source: $relative" }
        val attributes = Files.readAttributes(resolved, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            attributes.isDirectory -> {
                require(ancestors.add(resolved)) { "Read-only mount contains a symlink cycle: $relative" }
                try {
                    if (destination != null && relative.isNotEmpty()) Files.createDirectory(destination)
                    digest.record("directory", relative)
                    Files.newDirectoryStream(resolved).use { children ->
                        children.toList().sortedBy { it.fileName.toString() }.forEach { child ->
                            val name = child.fileName.toString()
                            visit(
                                sourceRoot, child, destination?.resolve(name),
                                if (relative.isEmpty()) name else "$relative/$name", ancestors, digest,
                                allowSymlinks, accountBytes,
                            )
                        }
                    }
                } finally {
                    ancestors.remove(resolved)
                }
            }

            attributes.isRegularFile -> {
                val contentDigest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(resolved, READ, NOFOLLOW_LINKS).use { input ->
                    val output = destination?.let { Files.newOutputStream(it, CREATE_NEW, WRITE, NOFOLLOW_LINKS) }
                    output.use {
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            accountBytes(count)
                            output?.write(buffer, 0, count)
                            contentDigest.update(buffer, 0, count)
                        }
                    }
                }
                val executable = Files.isExecutable(resolved)
                if (destination != null) {
                    destination.toFile().setExecutable(executable, false)
                    Files.setLastModifiedTime(destination, attributes.lastModifiedTime())
                }
                digest.record(
                    "file", relative, executable.toString(),
                    attributes.lastModifiedTime().toString(), contentDigest.digest().toHex(),
                )
            }

            else -> throw IllegalArgumentException("Read-only mount contains an unsupported file: $relative")
        }
    }

    private fun requireCapacity(directory: Path, newBytes: Long) {
        var bytes = 0L
        var generations = 0
        Files.newDirectoryStream(directory).use { children ->
            children.forEach { child ->
                generations++
                Files.walk(child).use { paths ->
                    paths.forEach { path ->
                        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (attributes.isRegularFile) bytes += attributes.size()
                    }
                }
            }
        }
        require(generations < maxGenerations && bytes <= maxStorageBytes - newBytes) {
            "Read-only snapshot storage budget exhausted ($maxGenerations generations / $maxStorageBytes bytes); " +
                "retained snapshots may still be used by terminals or background processes"
        }
    }

    private fun MessageDigest.record(vararg fields: String) {
        fields.forEach { field ->
            update(field.toByteArray(Charsets.UTF_8))
            update(0.toByte())
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // Only unpublished staging trees are discarded; Files.walk does not follow symbolic links.
    private fun discard(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    companion object {
        const val DIRECTORY = "read-only-snapshots"
        private val storageLocks = ConcurrentHashMap<String, Any>()
    }
}
