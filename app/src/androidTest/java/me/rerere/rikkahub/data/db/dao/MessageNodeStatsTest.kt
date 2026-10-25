package me.yui.yuihub.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.yui.yuihub.data.db.AppDatabase
import me.yui.yuihub.data.db.entity.ConversationEntity
import me.yui.yuihub.data.db.entity.MessageNodeEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNodeStatsTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MessageNodeDAO
    private var nodeIndex = 0

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.messageNodeDao()
        database.conversationDao().insert(
            ConversationEntity(
                id = "stats-test",
                assistantId = "assistant",
                title = "Stats test",
                nodes = "[]",
                createAt = 0,
                updateAt = 0,
                isPinned = false,
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun malformedRowsAreSkippedWhileValidMessagesAreAggregated() = runBlocking {
        insertMessages("""[{"role":"user"""")
        insertMessages(
            """[
                {"role":"user","createdAt":"2026-08-12T09:00:00"},
                {"role":"assistant","createdAt":"2026-08-12T09:01:00",
                 "usage":{"promptTokens":100,"completionTokens":40,"cachedTokens":20}},
                {"role":"user","createdAt":"2026-08-12T10:00:00"},
                {"role":"user","createdAt":"2026-08-13T09:00:00"},
                {"role":"user","createdAt":"2026-08-01T09:00:00"}
            ]""".trimIndent()
        )
        insertMessages("not json")
        insertMessages(
            """[{"role":"assistant","createdAt":"2026-08-13T09:01:00",
                 "usage":{"promptTokens":50,"completionTokens":10,"cachedTokens":5}}]"""
        )
        insertMessages("[]")

        assertEquals(MessageUsageStats(6, 2, 150, 50, 25), dao.getConversationUsageStats("stats-test"))
        assertEquals(5, dao.getNodesOfConversation("stats-test").size)
    }

    @Test
    fun onlyMalformedOrEmptyRowsProduceEmptyStats() = runBlocking {
        insertMessages("")
        insertMessages("[")
        insertMessages("not json")
        insertMessages("[]")

        assertEquals(MessageUsageStats(), dao.getConversationUsageStats("stats-test"))
    }

    @Test
    fun emptyDatabaseProducesEmptyStats() = runBlocking {
        assertEquals(MessageUsageStats(), dao.getConversationUsageStats("stats-test"))
    }

    private suspend fun insertMessages(messages: String) {
        val index = nodeIndex++
        dao.insert(
            MessageNodeEntity(
                id = "node-$index",
                conversationId = "stats-test",
                nodeIndex = index,
                messages = messages,
                selectIndex = 0,
            )
        )
    }
}
