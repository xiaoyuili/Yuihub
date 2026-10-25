package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReadOnlyMountSnapshotsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root = "test-workspace"

    private fun source() = temporaryFolder.newFolder().also {
        File(it, "original.txt").writeText("original")
    }

    private fun mount(source: File, target: String = "/upload", readOnly: Boolean = true) =
        WorkspaceBindMount(source, target, readOnly)

    @Test
    fun snapshotWritesAndNewFilesDoNotChangeOriginals() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val snapshot = snapshots.prepare(listOf(mount(source)), temporaryFolder.newFolder()).single()

        File(snapshot.source, "original.txt").writeText("shell edit")
        File(snapshot.source, "new.txt").writeText("shell file")

        assertEquals("original", File(source, "original.txt").readText())
        assertFalse(File(source, "new.txt").exists())
        assertFalse(Files.isSameFile(File(source, "original.txt").toPath(), File(snapshot.source, "original.txt").toPath()))
        assertFalse(snapshot.readOnly)
    }

    @Test
    fun sourceChangesCreateNewGenerationWithoutRefreshingOldSnapshot() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        fun prepare() = snapshots.prepare(listOf(mount(source)), directory).single().source
        val first = prepare()
        assertEquals(first, prepare())
        val original = File(source, "original.txt")
        val mtime = Files.getLastModifiedTime(original.toPath())
        original.writeText("replaced") // Same byte count and mtime must still invalidate the snapshot.
        Files.setLastModifiedTime(original.toPath(), mtime)
        File(source, "new.txt").writeText("new upload")
        val second = prepare()

        assertNotEquals(first, second)
        assertEquals("original", File(first, "original.txt").readText())
        assertFalse(File(first, "new.txt").exists())
        assertEquals("replaced", File(second, "original.txt").readText())
        assertEquals("new upload", File(second, "new.txt").readText())
        assertTrue(original.delete())
        assertFalse(File(prepare(), "original.txt").exists())
    }

    @Test
    fun timestampChangesAreVisibleInNewSnapshot() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        val changedTime = FileTime.fromMillis(File(source, "original.txt").lastModified() + 5000)
        Files.setLastModifiedTime(File(source, "original.txt").toPath(), changedTime)
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source

        assertNotEquals(first, second)
        assertEquals(changedTime, Files.getLastModifiedTime(File(second, "original.txt").toPath()))
    }

    @Test
    fun internalSymlinksAreMaterializedAsIndependentFilesAndDirectories() {
        val source = source()
        val nested = File(source, "nested").apply { mkdirs() }
        File(nested, "data.txt").writeText("nested original")
        Files.createSymbolicLink(File(source, "alias.txt").toPath(), File("original.txt").toPath())
        Files.createSymbolicLink(File(source, "alias-dir").toPath(), nested.toPath())
        val snapshot = ReadOnlyMountSnapshots().prepare(listOf(mount(source)), temporaryFolder.newFolder()).single().source

        assertFalse(Files.isSymbolicLink(File(snapshot, "alias.txt").toPath()))
        assertFalse(Files.isSymbolicLink(File(snapshot, "alias-dir").toPath()))
        File(snapshot, "alias.txt").writeText("edit")
        File(snapshot, "alias-dir/data.txt").writeText("edit")
        assertEquals("original", File(source, "original.txt").readText())
        assertEquals("nested original", File(nested, "data.txt").readText())
        assertEquals("original", File(snapshot, "original.txt").readText())
    }

    @Test
    fun escapingSymlinkFailsWithoutPublishingSnapshot() {
        val source = source()
        val outside = temporaryFolder.newFile().also { it.writeText("outside") }
        Files.createSymbolicLink(File(source, "escape").toPath(), outside.toPath())
        val directory = temporaryFolder.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyMountSnapshots().prepare(listOf(mount(source)), directory)
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals("outside", outside.readText())
    }

    @Test
    fun danglingAndCyclicSymlinksFailClosed() {
        val source = source()
        val link = File(source, "link").toPath()
        Files.createSymbolicLink(link, File("missing").toPath())
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        assertThrows(java.io.IOException::class.java) { snapshots.prepare(listOf(mount(source)), directory) }
        Files.delete(link)
        Files.createSymbolicLink(link, source.toPath())
        assertThrows(IllegalArgumentException::class.java) { snapshots.prepare(listOf(mount(source)), directory) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun poisonedPublishedSnapshotIsNeverUsedAsSyncDestination() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        val copied = File(first, "original.txt").toPath()
        Files.delete(copied)
        Files.createSymbolicLink(copied, File(source, "original.txt").toPath())
        Files.createSymbolicLink(File(first, "nested").toPath(), source.toPath())
        File(source, "nested").mkdirs()
        File(source, "nested/new.txt").writeText("source addition")
        File(source, "original.txt").writeText("source update")
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source

        assertNotEquals(first, second)
        assertTrue(Files.isSymbolicLink(copied))
        assertEquals("source update", File(source, "original.txt").readText())
        assertFalse(File(source, "new.txt").exists())
        assertEquals("source addition", File(second, "nested/new.txt").readText())
        assertFalse(Files.isSymbolicLink(File(second, "nested").toPath()))
    }

    @Test
    fun snapshotStorageCannotBeInsideSource() {
        val source = source()
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyMountSnapshots().prepare(listOf(mount(source)), File(source, "snapshots"))
        }
    }

    @Test
    fun missingReadOnlySourceIsSkippedButDanglingSourceSymlinkIsRejected() {
        val missing = File(temporaryFolder.root, "missing")
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        assertTrue(snapshots.prepare(listOf(mount(missing)), directory).isEmpty())
        val link = File(temporaryFolder.root, "dangling").toPath()
        Files.createSymbolicLink(link, missing.toPath())
        assertThrows(java.io.IOException::class.java) { snapshots.prepare(listOf(mount(link.toFile())), directory) }
        assertThrows(IllegalArgumentException::class.java) { mount(source()).prootBindSpec() }
    }

    @Test
    fun pollutedSnapshotIsReplacedEvenWhenOriginalIsUnchanged() {
        val source = source()
        val directory = temporaryFolder.newFolder()
        val snapshots = ReadOnlyMountSnapshots()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        File(first, "original.txt").writeText("polluted")
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source
        assertNotEquals(first, second)
        assertEquals("original", File(second, "original.txt").readText())
        assertEquals("polluted", File(first, "original.txt").readText())
        assertEquals(2, directory.listFiles().orEmpty().size)
    }

    @Test
    fun symlinkPollutionIsReplacedWithoutFollowingLinkOrChangingOriginal() {
        val source = source()
        val directory = temporaryFolder.newFolder()
        val snapshots = ReadOnlyMountSnapshots()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        Files.delete(File(first, "original.txt").toPath())
        Files.createSymbolicLink(File(first, "original.txt").toPath(), File(source, "original.txt").toPath())
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source
        assertNotEquals(first, second)
        assertFalse(Files.isSymbolicLink(File(second, "original.txt").toPath()))
        assertEquals("original", File(source, "original.txt").readText())
    }

    @Test
    fun snapshotBudgetRejectsNewGenerationsWithoutDeletingExistingOnes() {
        val source = source()
        val directory = temporaryFolder.newFolder()
        val snapshots = ReadOnlyMountSnapshots(maxStorageBytes = 20, maxGenerations = 2)
        val first = snapshots.prepare(listOf(mount(source)), directory, reuseUnchanged = false).single().source
        val second = snapshots.prepare(listOf(mount(source)), directory, reuseUnchanged = false).single().source
        val error = assertThrows(IllegalArgumentException::class.java) {
            snapshots.prepare(listOf(mount(source)), directory, reuseUnchanged = false)
        }
        assertTrue(error.message!!.contains("storage budget exhausted"))
        assertEquals("original", File(first, "original.txt").readText())
        assertEquals("original", File(second, "original.txt").readText())
        assertEquals(2, directory.listFiles().orEmpty().size)
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyMountSnapshots(maxStorageBytes = 1).prepare(listOf(mount(source)), temporaryFolder.newFolder())
        }
    }

    @Test
    fun replacingGenerationRootWithSymlinkCannotBindOriginalOnNextCall() {
        val source = source()
        val directory = temporaryFolder.newFolder()
        val snapshots = ReadOnlyMountSnapshots()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        Files.delete(File(first, "original.txt").toPath())
        Files.delete(first.toPath())
        Files.createSymbolicLink(first.toPath(), source.toPath())
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source
        assertNotEquals(first, second)
        assertFalse(Files.isSymbolicLink(second.toPath()))
        File(second, "original.txt").writeText("edit")
        assertEquals("original", File(source, "original.txt").readText())
    }

    @Test
    fun missingSourceAppearingLaterIsSnapshotted() {
        val source = File(temporaryFolder.root, "later")
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        assertTrue(snapshots.prepare(listOf(mount(source)), directory).isEmpty())
        source.mkdirs()
        File(source, "new.txt").writeText("later upload")
        val snapshot = snapshots.prepare(listOf(mount(source)), directory).single().source
        assertNotEquals(source, snapshot)
        assertEquals("later upload", File(snapshot, "new.txt").readText())
    }

    @Test
    fun concurrentPreparationPublishesCompleteGenerations() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = executor.invokeAll((1..8).map {
                Callable { snapshots.prepare(listOf(mount(source)), directory).single().source }
            })
            val generations = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, generations.distinct().size)
            generations.forEach { assertEquals("original", File(it, "original.txt").readText()) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun managerExecutionUsesSnapshotsButFileToolsStillReadOriginals() {
        val source = source()
        val writable = temporaryFolder.newFolder()
        var captured: WorkspaceShellContext? = null
        val manager = WorkspaceManager(
            baseDir = temporaryFolder.newFolder(),
            bindMounts = listOf(mount(source), mount(writable, "/editable", false)),
            shellRunner = object : WorkspaceShellRunner {
                override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                    captured = context
                    File(context.bindMounts.single { it.target == "/upload" }.source, "original.txt").writeText("shell edit")
                    return WorkspaceCommandResult(0, "", "")
                }
            },
        )
        manager.ensureWorkspace(root)
        manager.executeCommand(root, "edit")
        val firstContext = checkNotNull(captured)
        val first = firstContext.bindMounts.single { it.target == "/upload" }.source
        assertNotEquals(source, first)
        assertEquals(writable, firstContext.bindMounts.single { it.target == "/editable" }.source)
        assertEquals(source, manager.resolveRootfsPath(root, "/upload/original.txt").rootDir)
        ByteArrayOutputStream().also {
            manager.exportRootfsFile(root, "/upload/original.txt", it)
            assertEquals("original", it.toString("UTF-8"))
        }
        File(source, "new.txt").writeText("new upload")
        manager.executeCommand(root, "edit", shellCompatibilityMode = true)
        val secondContext = checkNotNull(captured)
        val second = secondContext.bindMounts.single { it.target == "/upload" }.source
        assertNotEquals(first, second)
        assertTrue(secondContext.shellCompatibilityMode)
        assertEquals("new upload", File(second, "new.txt").readText())
        assertEquals("original", File(source, "original.txt").readText())
    }

    @Test
    fun externalTerminalSpecsUseIndependentSnapshotsForGlobalAndCustomMounts() {
        val upload = source()
        val custom = source()
        val writable = temporaryFolder.newFolder()
        val manager = WorkspaceManager(
            baseDir = temporaryFolder.newFolder(),
            bindMounts = listOf(mount(upload), mount(writable, "/editable", false)),
        )
        manager.ensureWorkspace(root)
        val extras = listOf(mount(custom, "/reference"))
        fun specs() = manager.buildProotArgs(root, extraBindMounts = extras)
        fun sourceFor(args: List<String>, target: String) =
            File(args.single { it.endsWith(":$target") }.removeSuffix(":$target"))
        val first = specs()
        val firstUpload = sourceFor(first, "/upload")
        val firstCustom = sourceFor(first, "/reference")
        assertNotEquals(upload, firstUpload)
        assertNotEquals(custom, firstCustom)
        assertEquals(writable, sourceFor(first, "/editable"))
        File(firstUpload, "original.txt").writeText("terminal edit")
        Files.createSymbolicLink(File(firstCustom, "attack").toPath(), custom.toPath())
        File(upload, "new.txt").writeText("new upload")
        val second = specs()
        val secondUpload = sourceFor(second, "/upload")
        assertNotEquals(firstUpload, secondUpload)
        assertNotEquals(firstCustom, sourceFor(second, "/reference"))
        assertEquals("original", File(upload, "original.txt").readText())
        assertEquals("original", File(secondUpload, "original.txt").readText())
        assertEquals("new upload", File(secondUpload, "new.txt").readText())
        assertEquals("terminal edit", File(firstUpload, "original.txt").readText())
        assertFalse(File(firstUpload, "new.txt").exists())
        assertFalse(first.any { it == "${upload.absolutePath}:/upload" || it == "${custom.absolutePath}:/reference" })
    }

    @Test
    fun directRunnerPreparationProtectsPersistentAndCompatibilityEntrypoints() {
        val source = source()
        val workspace = temporaryFolder.newFolder()
        val files = File(workspace, "files").apply { mkdirs() }
        val context = WorkspaceShellContext(
            root, "edit", "", files, File(workspace, "linux"), File(workspace, "tmp"), files,
            1000, bindMounts = listOf(mount(source)),
        )
        val runner = ProotShellRunner(temporaryFolder.newFolder())
        val persistent = runner.prepareExecutionContext(context)
        val persistentSource = persistent.bindMounts.single().source
        assertNotEquals(source, persistentSource)
        assertEquals(persistentSource, runner.prepareExecutionContext(context).bindMounts.single().source)
        val compatible = runner.prepareExecutionContext(context.copy(shellCompatibilityMode = true))
        assertNotEquals(persistentSource, compatible.bindMounts.single().source)
        File(compatible.bindMounts.single().source, "original.txt").writeText("compatibility edit")
        assertEquals("original", File(source, "original.txt").readText())
        assertTrue(compatible.bindMounts.single().prootBindSpec().endsWith(":/upload"))
    }
}
