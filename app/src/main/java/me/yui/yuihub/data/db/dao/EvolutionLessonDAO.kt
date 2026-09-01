package me.yui.yuihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.yui.yuihub.data.db.entity.EvolutionLessonEntity

@Dao
interface EvolutionLessonDAO {
    @Query("SELECT * FROM evolution_lesson WHERE assistant_id = :assistantId ORDER BY updated_at DESC")
    fun getLessonsFlow(assistantId: String): Flow<List<EvolutionLessonEntity>>

    @Query("SELECT * FROM evolution_lesson WHERE assistant_id = :assistantId ORDER BY updated_at DESC")
    suspend fun getLessons(assistantId: String): List<EvolutionLessonEntity>

    @Query("SELECT * FROM evolution_lesson WHERE id = :id")
    suspend fun getById(id: Int): EvolutionLessonEntity?

    @Insert
    suspend fun insert(lesson: EvolutionLessonEntity): Long

    @Update
    suspend fun update(lesson: EvolutionLessonEntity)

    @Query("DELETE FROM evolution_lesson WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM evolution_lesson WHERE assistant_id = :assistantId")
    suspend fun deleteByAssistant(assistantId: String)
}
