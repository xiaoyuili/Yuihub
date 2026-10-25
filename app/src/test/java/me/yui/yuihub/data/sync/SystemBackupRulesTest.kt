package me.yui.yuihub.data.sync

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class SystemBackupRulesTest {
    @Test
    fun `system backup and device transfer share the upload allowlist`() {
        val factory = DocumentBuilderFactory.newInstance()
        val legacy = factory.newDocumentBuilder()
            .parse(File("src/main/res/xml/backup_rules.xml")).documentElement
        val modern = factory.newDocumentBuilder()
            .parse(File("src/main/res/xml/data_extraction_rules.xml")).documentElement
        val policies = listOf(
            legacy,
            modern.getElementsByTagName("cloud-backup").item(0) as Element,
            modern.getElementsByTagName("device-transfer").item(0) as Element,
        )

        policies.forEach { policy ->
            val includes = policy.getElementsByTagName("include")
            assertEquals(1, includes.length)
            val include = includes.item(0) as Element
            assertEquals("file", include.getAttribute("domain"))
            assertEquals("upload/", include.getAttribute("path"))
            assertEquals(0, policy.getElementsByTagName("exclude").length)
        }
    }
}
