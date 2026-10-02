package me.yui.yuihub.data.datastore

import kotlinx.serialization.json.Json
import me.yui.yuihub.utils.JsonInstant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实的 RikkaHub 备份 settings.json 验证反序列化兼容性。
 * fixture 来自用户提供的备份（providers 20 / lorebooks 1 / assistants 2）。
 */
class RikkaHubSettingsImportTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun loadFixture(): String {
        val text = javaClass.classLoader!!
            .getResourceAsStream("rikkahub_settings_fixture.json")!!
            .readBytes().decodeToString()
        return me.yui.yuihub.data.datastore.migration.SettingsJsonMigrator.migrate(text)
    }

    @Test
    fun `upstream settings deserializes with providers and lorebooks`() {
        val settings = json.decodeFromString<Settings>(loadFixture())

        println("providers=${settings.providers.size}")
        println("lorebooks=${settings.lorebooks.size}")
        println("assistants=${settings.assistants.size}")
        settings.providers.forEach { p ->
            println("  provider: type=${p::class.simpleName} name=${p.name} models=${p.models.size}")
        }
        settings.lorebooks.forEach { b ->
            println("  lorebook: name=${b.name} entries=${b.entries.size}")
        }

        assertTrue("providers should not be empty", settings.providers.isNotEmpty())
        assertTrue("lorebooks should not be empty", settings.lorebooks.isNotEmpty())
        assertTrue("assistants should not be empty", settings.assistants.isNotEmpty())
    }
}
