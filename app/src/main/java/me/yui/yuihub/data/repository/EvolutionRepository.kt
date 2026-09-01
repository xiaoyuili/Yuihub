package me.yui.yuihub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.yui.yuihub.data.db.dao.EvolutionLessonDAO
import me.yui.yuihub.data.db.entity.EvolutionLessonEntity
import me.yui.yuihub.data.model.EvolutionLesson

class EvolutionRepository(
    private val dao: EvolutionLessonDAO,
) {
    companion object {
        const val MAX_LESSONS_PER_ASSISTANT = 40
        const val PROMPT_CHAR_BUDGET = 1600
    }

    fun getLessonsFlow(assistantId: String): Flow<List<EvolutionLesson>> =
        dao.getLessonsFlow(assistantId).map { rows -> rows.map { it.toModel() } }

    suspend fun getLessons(assistantId: String): List<EvolutionLesson> =
        dao.getLessons(assistantId).map { it.toModel() }

    suspend fun selectForPrompt(assistantId: String): List<EvolutionLesson> {
        val all = getLessons(assistantId)
        if (all.isEmpty()) return emptyList()
        val selected = mutableListOf<EvolutionLesson>()
        var used = 0
        for (lesson in all) {
            val cost = lesson.title.length + lesson.content.length
            if (used + cost > PROMPT_CHAR_BUDGET && selected.isNotEmpty()) continue
            selected += lesson
            used += cost
        }
        return selected
    }

    suspend fun addLesson(
        assistantId: String,
        kind: String,
        title: String,
        content: String,
    ): EvolutionLesson {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            EvolutionLessonEntity(
                assistantId = assistantId,
                kind = kind,
                title = title.trim(),
                content = content.trim(),
                createdAt = now,
                updatedAt = now,
            )
        ).toInt()
        trim(assistantId)
        return dao.getById(id)!!.toModel()
    }

    suspend fun updateLesson(id: Int, title: String, content: String, kind: String) {
        val old = dao.getById(id) ?: return
        dao.update(
            old.copy(
                title = title.trim(),
                content = content.trim(),
                kind = kind,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteLesson(id: Int) {
        dao.deleteById(id)
    }

    suspend fun deleteByAssistant(assistantId: String) {
        dao.deleteByAssistant(assistantId)
    }

    private suspend fun trim(assistantId: String) {
        val all = dao.getLessons(assistantId)
        if (all.size <= MAX_LESSONS_PER_ASSISTANT) return
        all.drop(MAX_LESSONS_PER_ASSISTANT).forEach { dao.deleteById(it.id) }
    }
}

private fun EvolutionLessonEntity.toModel() = EvolutionLesson(
    id = id,
    assistantId = assistantId,
    kind = kind,
    title = title,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
