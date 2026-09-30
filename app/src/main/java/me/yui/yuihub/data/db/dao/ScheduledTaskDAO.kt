package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.yui.yuihub.data.db.entity.ScheduledTaskEntity

@Dao
interface ScheduledTaskDAO {
    @Query("SELECT * FROM scheduled_task ORDER BY created_at DESC")
    fun getAllFlow(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_task WHERE id = :id")
    suspend fun getById(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_task WHERE enabled = 1")
    suspend fun getAllEnabled(): List<ScheduledTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Delete
    suspend fun delete(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_task WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "UPDATE scheduled_task SET last_run_at = :runAt, last_run_status = :status, " +
            "last_conversation_id = :conversationId WHERE id = :id"
    )
    suspend fun updateRunState(id: String, runAt: Long, status: String, conversationId: String)

    @Query("UPDATE scheduled_task SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)
}
