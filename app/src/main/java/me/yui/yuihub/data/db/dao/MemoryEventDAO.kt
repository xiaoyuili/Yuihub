package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.yui.yuihub.data.db.entity.MemoryEventEntity

@Dao
interface MemoryEventDAO {
    @Insert
    suspend fun insert(event: MemoryEventEntity): Long

    @Query("SELECT * FROM MemoryEventEntity WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :limit")
    fun flowOfAssistant(assistantId: String, limit: Int = 40): Flow<List<MemoryEventEntity>>

    @Query("SELECT * FROM MemoryEventEntity WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getOfAssistant(assistantId: String, limit: Int = 40): List<MemoryEventEntity>

    @Query("SELECT MAX(created_at) FROM MemoryEventEntity WHERE assistant_id = :assistantId AND type = :type")
    suspend fun lastAt(assistantId: String, type: String): Long?

    @Query("DELETE FROM MemoryEventEntity WHERE assistant_id = :assistantId")
    suspend fun deleteOfAssistant(assistantId: String)
}
