package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.yui.yuihub.data.db.entity.MemoryUsageEntity

@Dao
interface MemoryUsageDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(usage: MemoryUsageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(usages: List<MemoryUsageEntity>)

    @Query("SELECT memory_id FROM MemoryUsageEntity WHERE conversation_id = :conversationId AND source IN (:sources)")
    suspend fun memoryIdsOf(conversationId: String, sources: List<String>): List<Int>

    @Query("DELETE FROM MemoryUsageEntity WHERE conversation_id = :conversationId")
    suspend fun deleteOfConversation(conversationId: String)

    @Query("DELETE FROM MemoryUsageEntity WHERE conversation_id = :conversationId AND source IN ('injected', 'searched')")
    suspend fun clearSeenOfConversation(conversationId: String)

    @Query("DELETE FROM MemoryUsageEntity WHERE assistant_id = :assistantId")
    suspend fun deleteOfAssistant(assistantId: String)
}
