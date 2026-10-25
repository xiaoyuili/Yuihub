package me.yui.yuihub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PendingRestoreStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun source(text: String = "restored database"): File =
        temporary.newFile().apply { writeText(text) }

    @Test
    fun `staging does not replace live database`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db").apply { writeText("original") }
        PendingRestoreStore.stage(files, source())
        assertEquals("original", live.readText())
        assertTrue(PendingRestoreStore.hasPending(files))
        assertEquals("restored database", File(PendingRestoreStore.pendingDir(files), "staged.db").readText())
    }

    @Test
    fun `cancelled staging does not commit pending marker or change live database`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db").apply { writeText("original") }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            PendingRestoreStore.stage(files, source()) { throw java.util.concurrent.CancellationException() }
        }
        assertFalse(PendingRestoreStore.hasPending(files))
        assertEquals("original", live.readText())
    }

    @Test
    fun `second restore cannot overwrite committed pending payload`() {
        val files = temporary.newFolder("files")
        PendingRestoreStore.stage(files, source("first"))
        assertThrows(IllegalStateException::class.java) { PendingRestoreStore.stage(files, source("second")) }
        assertEquals("first", File(PendingRestoreStore.pendingDir(files), "staged.db").readText())
    }

    @Test
    fun `validation failure preserves live db sidecars and pending for retry`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db").apply { writeText("original") }
        val wal = File(live.parentFile, "${live.name}-wal").apply { writeText("wal") }
        PendingRestoreStore.stage(files, source())
        assertThrows(IOException::class.java) {
            PendingRestoreStore.applyPending(files, live) { throw IOException("bad database") }
        }
        assertEquals("original", live.readText())
        assertEquals("wal", wal.readText())
        assertTrue(PendingRestoreStore.hasPending(files))
    }

    @Test
    fun `missing staged payload fails closed without clearing marker`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db").apply { writeText("original") }
        PendingRestoreStore.stage(files, source())
        File(PendingRestoreStore.pendingDir(files), "staged.db").delete()
        assertThrows(IllegalStateException::class.java) { PendingRestoreStore.applyPending(files, live) {} }
        assertEquals("original", live.readText())
        assertTrue(PendingRestoreStore.hasPending(files))
    }

    @Test
    fun `startup installation replaces db removes old sidecars and clears pending`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db").apply { writeText("original") }
        val wal = File(live.parentFile, "${live.name}-wal").apply { writeText("old wal") }
        val shm = File(live.parentFile, "${live.name}-shm").apply { writeText("old shm") }
        PendingRestoreStore.stage(files, source())
        var validated = false
        assertTrue(PendingRestoreStore.applyPending(files, live) {
            assertEquals("restored database", it.readText())
            validated = true
        })
        assertTrue(validated)
        assertEquals("restored database", live.readText())
        assertFalse(wal.exists())
        assertFalse(shm.exists())
        assertFalse(PendingRestoreStore.hasPending(files))
        assertFalse(PendingRestoreStore.applyPending(files, live) { error("Must not validate twice") })
    }

    @Test
    fun `attachments are staged without changing live data and installed with database`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db").apply { writeText("original database") }
        val target = File(files, "upload/attachment.txt").apply { parentFile!!.mkdirs(); writeText("original attachment") }
        PendingRestoreStore.stage(files, source(), attachments = mapOf(source("restored attachment") to target))
        assertEquals("original attachment", target.readText())
        assertEquals("original database", live.readText())
        assertTrue(PendingRestoreStore.applyPending(files, live) {})
        assertEquals("restored attachment", target.readText())
        assertEquals("restored database", live.readText())
        assertFalse(PendingRestoreStore.hasPending(files))
    }

    @Test
    fun `attachment copy failure does not publish marker or overwrite live file`() {
        val files = temporary.newFolder("files")
        val target = File(files, "upload/file.txt").apply { parentFile!!.mkdirs(); writeText("original") }
        assertThrows(IOException::class.java) {
            PendingRestoreStore.stage(files, source(), attachments = mapOf(File(files, "missing") to target))
        }
        assertFalse(PendingRestoreStore.hasPending(files))
        assertEquals("original", target.readText())
    }

    @Test
    fun `attachment installation failure keeps pending and database for retry`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db").apply { writeText("original") }
        val target = File(files, "upload/file.txt").apply { mkdirs(); File(this, "busy").writeText("busy") }
        PendingRestoreStore.stage(files, source(), attachments = mapOf(source("attachment") to target))
        assertThrows(IllegalStateException::class.java) { PendingRestoreStore.applyPending(files, live) {} }
        assertEquals("original", live.readText())
        assertTrue(PendingRestoreStore.hasPending(files))
        File(target, "busy").delete()
        target.delete()
        assertTrue(PendingRestoreStore.applyPending(files, live) {})
        assertEquals("attachment", target.readText())
    }

    @Test
    fun `settings are staged without writing live preferences`() {
        val files = temporary.newFolder("files")
        val preferences = File(files, "datastore/settings.preferences_pb").apply { parentFile!!.mkdirs(); writeText("original") }
        PendingRestoreStore.stage(files, settings = "{}")
        assertTrue(PendingRestoreStore.hasPending(files))
        assertEquals("original", preferences.readText())
        assertThrows(IllegalStateException::class.java) {
            PendingRestoreStore.applyPending(files, temporary.newFile("live.db")) {}
        }
        assertTrue(PendingRestoreStore.hasPending(files))
    }

    @Test
    fun `legacy database marker remains installable`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db")
        PendingRestoreStore.stage(files, source())
        File(PendingRestoreStore.pendingDir(files), "pending.marker").writeText("17")
        assertTrue(PendingRestoreStore.applyPending(files, live) {})
        assertEquals("restored database", live.readText())
    }

    @Test
    fun `sidecar deletion failure retains retryable pending database`() {
        val files = temporary.newFolder("files")
        val live = temporary.newFile("live.db").apply { writeText("original") }
        File(live.parentFile, "${live.name}-wal").apply {
            mkdir()
            File(this, "busy").writeText("busy")
        }
        PendingRestoreStore.stage(files, source())
        assertThrows(IllegalStateException::class.java) { PendingRestoreStore.applyPending(files, live) {} }
        assertEquals("original", live.readText())
        assertTrue(PendingRestoreStore.hasPending(files))
    }
}
