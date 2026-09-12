package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.yui.yuihub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM MemoryEntity WHERE assistant_id = :assistantId ORDER BY updated_at DESC")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM MemoryEntity WHERE assistant_id = :assistantId ORDER BY updated_at DESC")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM MemoryEntity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM MemoryEntity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM MemoryEntity WHERE id IN (:ids)")
    suspend fun deleteMemories(ids: List<Int>)

    @Query("DELETE FROM MemoryEntity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
