package me.yui.yuihub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageFtsQueryTest {

    private val ascii = { text: String -> "S($text)" }

    @Test
    fun `chinese runs become near phrase and ascii goes through simple query`() {
        val query = buildFtsMatchQuery("深层链接 deep link 123", ascii)
        assertEquals(
            "NEAR(\"深\" \"层\" \"链\" \"接\", 4) AND S(deep) AND S(link) AND S(123)",
            query,
        )
    }

    @Test
    fun `single chinese character is a plain term`() {
        assertEquals("\"记\" AND S(memory)", buildFtsMatchQuery("记 memory", ascii))
    }

    @Test
    fun `blank keyword yields null`() {
        assertNull(buildFtsMatchQuery("   ", ascii))
    }
}
