package me.yui.yuihub.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDownloaderProgressTest {

    @Test
    fun `computes fraction of total`() {
        assertEquals(0.5f, downloadProgress(50, 100)!!, 0.001f)
        assertEquals(1f, downloadProgress(100, 100)!!, 0.001f)
        assertEquals(0f, downloadProgress(0, 100)!!, 0.001f)
    }

    @Test
    fun `clamps overshoot to one`() {
        // 某些服务端上报的已下载字节会略超过 Content-Length
        assertEquals(1f, downloadProgress(120, 100)!!, 0.001f)
    }

    @Test
    fun `unknown total returns null so callers show indeterminate progress`() {
        assertNull(downloadProgress(1024, 0))
        assertNull(downloadProgress(1024, -1))
        assertNull(downloadProgress(0, 0))
    }
}
