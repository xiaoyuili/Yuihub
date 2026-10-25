package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import me.yui.yuihub.data.db.entity.MemorySessionEntity

@Dao
interface MemorySessionDAO {
    @Query("SELECT * FROM MemorySessionEntity WHERE conversation_id = :conversationId")
    suspend fun get(conversationId: String): MemorySessionEntity?

    @Upsert
    suspend fun upsert(session: MemorySessionEntity)

    @Query("UPDATE MemorySessionEntity SET reinject_pending = 0, updated_at = :now WHERE conversation_id = :conversationId")
    suspend fun clearReinjectPending(conversationId: String, now: Long)

    @Query("DELETE FROM MemorySessionEntity WHERE conversation_id = :conversationId")
    suspend fun delete(conversationId: String)
}
