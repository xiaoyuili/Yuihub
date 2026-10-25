package me.yui.yuihub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.yui.yuihub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_39_40_Test {
    private val testDb = "migration-39-40-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate39To40_preservesConversationAndAddsNullableOverrides() {
        val conversationId = "conversation-39"
        val assistantId = "assistant-39"
        val createAt = 1_700_000_000_000L
        val updateAt = 1_700_000_000_001L

        helper.createDatabase(testDb, 39).apply {
            insert(
                "ConversationEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", conversationId)
                    put("assistant_id", assistantId)
                    put("title", "Conversation before migration")
                    put("nodes", "[]")
                    put("create_at", createAt)
                    put("update_at", updateAt)
                    put("is_pinned", 1)
                    put("custom_system_prompt", "System prompt")
                    put("mode_injection_ids", "[\"mode-1\"]")
                    put("lorebook_ids", "[\"lorebook-1\"]")
                    put("workspace_cwd", "/workspace")
                    put("folder_id", "folder-1")
                    put("parent_conversation_id", "parent-1")
                    put("compression_summaries", "[]")
                }
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 40, true)

        db.query(
            "SELECT * FROM ConversationEntity WHERE id = ?",
            arrayOf(conversationId)
        ).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(assistantId, cursor.getString(cursor.getColumnIndexOrThrow("assistant_id")))
            assertEquals("Conversation before migration", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("nodes")))
            assertEquals(createAt, cursor.getLong(cursor.getColumnIndexOrThrow("create_at")))
            assertEquals(updateAt, cursor.getLong(cursor.getColumnIndexOrThrow("update_at")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("is_pinned")))
            assertEquals("System prompt", cursor.getString(cursor.getColumnIndexOrThrow("custom_system_prompt")))
            assertEquals("[\"mode-1\"]", cursor.getString(cursor.getColumnIndexOrThrow("mode_injection_ids")))
            assertEquals("[\"lorebook-1\"]", cursor.getString(cursor.getColumnIndexOrThrow("lorebook_ids")))
            assertEquals("/workspace", cursor.getString(cursor.getColumnIndexOrThrow("workspace_cwd")))
            assertEquals("folder-1", cursor.getString(cursor.getColumnIndexOrThrow("folder_id")))
            assertEquals("parent-1", cursor.getString(cursor.getColumnIndexOrThrow("parent_conversation_id")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("compression_summaries")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("enabled_skills_override")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("mcp_servers_override")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("local_tools_override")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("subagent_persona_id")))
        }

        val enabledSkills = "[]"
        val mcpServers = "[\"server-1\"]"
        val localTools = "[\"tool-1\"]"
        val personaId = "persona-1"
        db.execSQL(
            """
                UPDATE ConversationEntity
                SET enabled_skills_override = ?, mcp_servers_override = ?,
                    local_tools_override = ?, subagent_persona_id = ?
                WHERE id = ?
            """.trimIndent(),
            arrayOf(enabledSkills, mcpServers, localTools, personaId, conversationId)
        )

        db.query(
            """
                SELECT enabled_skills_override, mcp_servers_override,
                       local_tools_override, subagent_persona_id
                FROM ConversationEntity WHERE id = ?
            """.trimIndent(),
            arrayOf(conversationId)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(enabledSkills, cursor.getString(cursor.getColumnIndexOrThrow("enabled_skills_override")))
            assertEquals(mcpServers, cursor.getString(cursor.getColumnIndexOrThrow("mcp_servers_override")))
            assertEquals(localTools, cursor.getString(cursor.getColumnIndexOrThrow("local_tools_override")))
            assertEquals(personaId, cursor.getString(cursor.getColumnIndexOrThrow("subagent_persona_id")))
        }

        db.close()
    }
}
