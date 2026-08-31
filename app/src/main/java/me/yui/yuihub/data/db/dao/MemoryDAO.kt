package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.yui.yuihub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)

    @Query(
        "UPDATE memoryentity SET last_accessed_at = :now, access_count = access_count + 1 WHERE id IN (:ids)"
    )
    suspend fun updateAccessStats(ids: List<Int>, now: Long)

    @Query("UPDATE memoryentity SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: Int, embedding: String)

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND embedding IS NULL")
    suspend fun getMemoriesWithoutEmbedding(assistantId: String): List<MemoryEntity>
}
