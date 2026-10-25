package me.yui.yuihub.data.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class RestoreByteBudgetTest {
    @Test
    fun `entry boundary is inclusive`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, RestoreByteBudget(4).entry(ByteArrayInputStream(bytes), 4).readBytes())
    }

    @Test
    fun `entry limit applies with unknown archive sizes`() {
        val input = RestoreByteBudget(100).entry(ByteArrayInputStream(ByteArray(5)), 4)
        assertThrows(IOException::class.java) { input.readBytes() }
    }

    @Test
    fun `total counts multiple entries including drained entries`() {
        val budget = RestoreByteBudget(6)
        budget.entry(ByteArrayInputStream(ByteArray(4)), 4).readBytes()
        assertThrows(IOException::class.java) {
            budget.entry(ByteArrayInputStream(ByteArray(3)), 4).readBytes()
        }
    }

    @Test
    fun `single byte reads cannot bypass limit`() {
        val input = RestoreByteBudget(2).entry(ByteArrayInputStream(byteArrayOf(255.toByte(), 0, 1)), 2)
        assertEquals(255, input.read())
        assertEquals(0, input.read())
        assertThrows(IOException::class.java) { input.read() }
    }

    @Test
    fun `cancellation propagates from stream copy`() {
        val input = RestoreByteBudget().entry(ByteArrayInputStream(ByteArray(32))) {
            throw CancellationException("cancelled")
        }
        assertThrows(CancellationException::class.java) { input.readBytes() }
    }
}
